using System.Buffers.Binary;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
using KeyViewer.Abstractions;

namespace KeyViewer.Core.Input;

public sealed class LinuxEvdevInputSource : IRoutingInputSource
{
    private const ushort EvSyn = 0x00;
    private const ushort EvKey = 0x01;
    private const nuint Eviocgrab = 0x40044590;
    private readonly string[] _configuredDevices;
    private readonly object _gate = new();
    private readonly object _routeGate = new();
    private readonly Dictionary<(string Device, ushort Source), ushort> _mappedSources = [];
    private readonly Dictionary<ushort, int> _mappedTargetReferences = [];
    private readonly HashSet<(string Device, ushort Source)> _suppressedSources = [];
    private CancellationTokenSource? _cancellation;
    private Task[] _readers = [];
    private LinuxUInputDevice? _uinput;

    public LinuxEvdevInputSource(params string[] configuredDevices)
    {
        _configuredDevices = configuredDevices
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(Path.GetFullPath)
            .Distinct(StringComparer.Ordinal)
            .ToArray();
    }

    public string Id => "linux-evdev-keyboard";
    public string DisplayName => "Linux evdev 键盘（/dev/input，支持拦截与映射）";
    public bool IsRunning { get; private set; }
    public Func<KeyInputEvent, KeyRouteContext>? Router { get; set; }
    public event EventHandler<KeyInputEvent>? Input;

    public void Start()
    {
        if (!OperatingSystem.IsLinux())
        {
            return;
        }

        lock (_gate)
        {
            if (IsRunning)
            {
                return;
            }

            var devices = DiscoverDevices().ToArray();
            if (devices.Length == 0)
            {
                return;
            }

            try
            {
                _uinput = new LinuxUInputDevice();
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                // A normal desktop user may have evdev read permission without uinput
                // write permission. Keep visual monitoring alive, but do not grab a
                // keyboard unless a replacement output device was created successfully.
                _uinput = null;
            }

            _cancellation = new CancellationTokenSource();
            _readers = devices
                .Select(path => Task.Run(() => ReadDeviceAsync(path, _cancellation.Token)))
                .ToArray();
            IsRunning = true;
        }
    }

    public void Stop()
    {
        Task[] readers;
        lock (_gate)
        {
            if (!IsRunning)
            {
                return;
            }

            _cancellation?.Cancel();
            readers = _readers;
            IsRunning = false;
        }

        try
        {
            Task.WaitAll(readers, TimeSpan.FromSeconds(2));
        }
        catch (AggregateException exception) when (exception.InnerExceptions.All(
                   inner => inner is OperationCanceledException or IOException or UnauthorizedAccessException))
        {
        }

        lock (_routeGate)
        {
            ReleaseMappedTargets();
            _mappedSources.Clear();
            _mappedTargetReferences.Clear();
            _suppressedSources.Clear();
        }
        _uinput?.Dispose();
        _uinput = null;
    }

    private IEnumerable<string> DiscoverDevices()
    {
        if (_configuredDevices.Length > 0)
        {
            return _configuredDevices.Where(File.Exists);
        }

        const string byId = "/dev/input/by-id";
        if (Directory.Exists(byId))
        {
            var keyboards = Directory.EnumerateFiles(byId, "*-event-kbd")
                .Where(CanRead)
                .ToArray();
            if (keyboards.Length > 0)
            {
                return keyboards;
            }
        }

        const string input = "/dev/input";
        return Directory.Exists(input)
            ? Directory.EnumerateFiles(input, "event*").Where(CanRead)
            : [];
    }

    private static bool CanRead(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            return stream.CanRead;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private async Task ReadDeviceAsync(string path, CancellationToken cancellationToken)
    {
        FileStream? stream = null;
        var grabbed = false;
        try
        {
            stream = OpenDevice(path, _uinput is not null);
            if (_uinput is not null && stream.CanWrite)
            {
                grabbed = Ioctl(stream.SafeFileHandle, Eviocgrab, 1) == 0;
            }

            var eventSize = IntPtr.Size == 8 ? 24 : 16;
            var typeOffset = eventSize - 8;
            var buffer = new byte[eventSize];
            while (!cancellationToken.IsCancellationRequested)
            {
                await stream.ReadExactlyAsync(buffer, cancellationToken).ConfigureAwait(false);
                var type = BinaryPrimitives.ReadUInt16LittleEndian(buffer.AsSpan(typeOffset, 2));
                if (!grabbed)
                {
                    if (type == EvKey)
                    {
                        _ = RouteKey(path, buffer, typeOffset, false);
                    }
                    continue;
                }

                if (type == EvKey)
                {
                    RouteKey(path, buffer, typeOffset, true);
                }
                else if (type == EvSyn)
                {
                    _uinput?.WriteEvent(buffer);
                }
            }
        }
        catch (Exception exception) when (exception is OperationCanceledException or IOException or UnauthorizedAccessException)
        {
        }
        finally
        {
            if (grabbed && stream is not null)
            {
                _ = Ioctl(stream.SafeFileHandle, Eviocgrab, 0);
            }
            stream?.Dispose();
        }
    }

    private FileStream OpenDevice(string path, bool requestWrite)
    {
        if (requestWrite)
        {
            try
            {
                return new FileStream(
                    path,
                    FileMode.Open,
                    FileAccess.ReadWrite,
                    FileShare.ReadWrite,
                    4096,
                    FileOptions.Asynchronous | FileOptions.SequentialScan);
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
            }
        }

        return new FileStream(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite,
            4096,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
    }

    private KeyRouteContext RouteKey(string path, byte[] rawEvent, int typeOffset, bool forward)
    {
        var code = BinaryPrimitives.ReadUInt16LittleEndian(rawEvent.AsSpan(typeOffset + 2, 2));
        var value = BinaryPrimitives.ReadInt32LittleEndian(rawEvent.AsSpan(typeOffset + 4, 4));
        var state = value switch
        {
            0 => KeyEventState.Up,
            1 => KeyEventState.Down,
            2 => KeyEventState.Repeat,
            _ => (KeyEventState?)null
        };
        if (state is null)
        {
            if (forward) _uinput?.WriteEvent(rawEvent);
            return new KeyRouteContext(new KeyInputEvent(
                LinuxKeyNames.ToName(code),
                KeyEventState.Repeat,
                $"linux:{path}",
                DateTimeOffset.UtcNow));
        }

        var input = new KeyInputEvent(
            LinuxKeyNames.ToName(code),
            state.Value,
            $"linux:{path}",
            DateTimeOffset.UtcNow);
        KeyRouteContext route;
        try
        {
            route = Router?.Invoke(input) ?? new KeyRouteContext(input);
            if (Router is null)
            {
                Input?.Invoke(this, input);
            }
        }
        catch
        {
            route = new KeyRouteContext(input);
        }

        if (forward)
        {
            ForwardRoutedEvent(path, code, state.Value, route, rawEvent, typeOffset);
        }
        return route;
    }

    private void ForwardRoutedEvent(
        string device,
        ushort sourceCode,
        KeyEventState state,
        KeyRouteContext route,
        byte[] rawEvent,
        int typeOffset)
    {
        lock (_routeGate)
        {
            var source = (device, sourceCode);
            if (state == KeyEventState.Up && _mappedSources.Remove(source, out var mappedTarget))
            {
                ReleaseMappedTarget(mappedTarget, rawEvent, typeOffset);
                return;
            }
            if (state == KeyEventState.Up && _suppressedSources.Remove(source))
            {
                return;
            }
            if (state == KeyEventState.Repeat && _mappedSources.TryGetValue(source, out mappedTarget))
            {
                WriteKey(rawEvent, typeOffset, mappedTarget, 2);
                return;
            }
            if (state == KeyEventState.Repeat && _suppressedSources.Contains(source))
            {
                return;
            }

            if (!route.ForwardToSystem)
            {
                if (state == KeyEventState.Down) _suppressedSources.Add(source);
                return;
            }

            var targetCode = LinuxKeyNames.ToCode(route.OutputKey);
            if (targetCode == 0 || targetCode == sourceCode)
            {
                _uinput?.WriteEvent(rawEvent);
                return;
            }

            if (state == KeyEventState.Down)
            {
                _mappedTargetReferences.TryGetValue(targetCode, out var references);
                _mappedTargetReferences[targetCode] = references + 1;
                _mappedSources[source] = targetCode;
                if (references == 0)
                {
                    WriteKey(rawEvent, typeOffset, targetCode, 1);
                }
                return;
            }

            _uinput?.WriteEvent(rawEvent);
        }
    }

    private void ReleaseMappedTarget(ushort target, byte[] template, int typeOffset)
    {
        _mappedTargetReferences.TryGetValue(target, out var references);
        if (references <= 1)
        {
            _mappedTargetReferences.Remove(target);
            WriteKey(template, typeOffset, target, 0);
        }
        else
        {
            _mappedTargetReferences[target] = references - 1;
        }
    }

    private void ReleaseMappedTargets()
    {
        var eventSize = IntPtr.Size == 8 ? 24 : 16;
        var typeOffset = eventSize - 8;
        var template = new byte[eventSize];
        BinaryPrimitives.WriteUInt16LittleEndian(template.AsSpan(typeOffset, 2), EvKey);
        foreach (var target in _mappedTargetReferences.Keys.ToArray())
        {
            WriteKey(template, typeOffset, target, 0);
        }
    }

    private void WriteKey(byte[] template, int typeOffset, ushort target, int value)
    {
        var output = (byte[])template.Clone();
        BinaryPrimitives.WriteUInt16LittleEndian(output.AsSpan(typeOffset, 2), EvKey);
        BinaryPrimitives.WriteUInt16LittleEndian(output.AsSpan(typeOffset + 2, 2), target);
        BinaryPrimitives.WriteInt32LittleEndian(output.AsSpan(typeOffset + 4, 4), value);
        _uinput?.WriteEvent(output);
    }

    private static int Ioctl(SafeFileHandle handle, nuint request, int argument)
        => ioctl(handle, request, argument);

    public void Dispose()
    {
        Stop();
        _cancellation?.Dispose();
    }

    [DllImport("libc", SetLastError = true)]
    private static extern int ioctl(SafeFileHandle file, nuint request, int argument);

    private sealed class LinuxUInputDevice : IDisposable
    {
        private const nuint UiSetEvbit = 0x40045564;
        private const nuint UiSetKeybit = 0x40045565;
        private const nuint UiDevCreate = 0x5501;
        private const nuint UiDevDestroy = 0x5502;
        private readonly object _writeGate = new();
        private readonly FileStream _stream;
        private bool _created;

        public LinuxUInputDevice()
        {
            var path = File.Exists("/dev/uinput") ? "/dev/uinput" : "/dev/input/uinput";
            _stream = new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            if (Ioctl(_stream.SafeFileHandle, UiSetEvbit, EvSyn) != 0
                || Ioctl(_stream.SafeFileHandle, UiSetEvbit, EvKey) != 0)
            {
                throw new IOException("Unable to configure /dev/uinput event types.");
            }
            for (var code = 0; code <= 0x2ff; code++)
            {
                if (Ioctl(_stream.SafeFileHandle, UiSetKeybit, code) != 0)
                {
                    throw new IOException($"Unable to configure /dev/uinput key {code}.");
                }
            }

            var setup = new byte[1116];
            var name = System.Text.Encoding.ASCII.GetBytes("KeyViewer Virtual Keyboard");
            name.CopyTo(setup, 0);
            BinaryPrimitives.WriteUInt16LittleEndian(setup.AsSpan(80, 2), 0x03);
            BinaryPrimitives.WriteUInt16LittleEndian(setup.AsSpan(82, 2), 0x4b56);
            BinaryPrimitives.WriteUInt16LittleEndian(setup.AsSpan(84, 2), 0x0001);
            BinaryPrimitives.WriteUInt16LittleEndian(setup.AsSpan(86, 2), 0x0001);
            _stream.Write(setup);
            _stream.Flush();
            if (Ioctl(_stream.SafeFileHandle, UiDevCreate, 0) != 0)
            {
                throw new IOException("Unable to create the KeyViewer uinput device.");
            }
            _created = true;
        }

        public void WriteEvent(ReadOnlySpan<byte> inputEvent)
        {
            lock (_writeGate)
            {
                _stream.Write(inputEvent);
                _stream.Flush();
            }
        }

        public void Dispose()
        {
            if (_created)
            {
                _ = Ioctl(_stream.SafeFileHandle, UiDevDestroy, 0);
                _created = false;
            }
            _stream.Dispose();
        }
    }
}

public static class LinuxKeyNames
{
    private static readonly Dictionary<ushort, string> Names = new()
    {
        [1] = "esc", [2] = "1", [3] = "2", [4] = "3", [5] = "4", [6] = "5",
        [7] = "6", [8] = "7", [9] = "8", [10] = "9", [11] = "0", [12] = "minus",
        [13] = "equal", [14] = "back", [15] = "tab", [16] = "q", [17] = "w",
        [18] = "e", [19] = "r", [20] = "t", [21] = "y", [22] = "u", [23] = "i",
        [24] = "o", [25] = "p", [26] = "lbracket", [27] = "rbracket", [28] = "enter",
        [29] = "lctrl", [30] = "a", [31] = "s", [32] = "d", [33] = "f", [34] = "g",
        [35] = "h", [36] = "j", [37] = "k", [38] = "l", [39] = "semicolon",
        [40] = "apostrophe", [41] = "grave", [42] = "lshift", [43] = "backslash",
        [44] = "z", [45] = "x", [46] = "c", [47] = "v", [48] = "b", [49] = "n",
        [50] = "m", [51] = "comma", [52] = "period", [53] = "slash", [54] = "rshift",
        [55] = "multiply", [56] = "lalt", [57] = "space", [58] = "capslock",
        [59] = "f1", [60] = "f2", [61] = "f3", [62] = "f4", [63] = "f5",
        [64] = "f6", [65] = "f7", [66] = "f8", [67] = "f9", [68] = "f10",
        [87] = "f11", [88] = "f12", [97] = "rctrl", [100] = "ralt", [102] = "home",
        [103] = "up", [104] = "pageup", [105] = "left", [106] = "right", [107] = "end",
        [108] = "down", [109] = "pagedown", [110] = "insert", [111] = "delete",
        [125] = "lwin", [126] = "rwin"
    };

    private static readonly Dictionary<string, ushort> Codes = Names
        .GroupBy(pair => pair.Value, StringComparer.OrdinalIgnoreCase)
        .ToDictionary(group => group.Key, group => group.First().Key, StringComparer.OrdinalIgnoreCase);

    public static string ToName(ushort code)
        => Names.TryGetValue(code, out var name) ? name : $"linux_{code}";

    public static ushort ToCode(string? name)
    {
        name = KeyNames.Normalize(name);
        if (name.Equals("backspace", StringComparison.OrdinalIgnoreCase)) name = "back";
        if (name.Equals("del", StringComparison.OrdinalIgnoreCase)) name = "delete";
        if (name.Equals("lmeta", StringComparison.OrdinalIgnoreCase)) name = "lwin";
        if (name.Equals("rmeta", StringComparison.OrdinalIgnoreCase)) name = "rwin";
        if (name.StartsWith("linux_", StringComparison.OrdinalIgnoreCase)
            && ushort.TryParse(name.AsSpan(6), out var raw))
        {
            return raw;
        }
        return Codes.TryGetValue(name, out var code) ? code : (ushort)0;
    }
}
