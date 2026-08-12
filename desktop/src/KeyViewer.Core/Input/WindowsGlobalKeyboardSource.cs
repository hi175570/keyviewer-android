using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using KeyViewer.Abstractions;

namespace KeyViewer.Core.Input;

public sealed class WindowsGlobalKeyboardSource : IRoutingInputSource
{
    private const int WhKeyboardLl = 13;
    private const int WmKeyDown = 0x0100;
    private const int WmKeyUp = 0x0101;
    private const int WmSysKeyDown = 0x0104;
    private const int WmSysKeyUp = 0x0105;
    private const int WmQuit = 0x0012;
    private const uint InputKeyboard = 1;
    private const uint KeyeventfKeyup = 0x0002;
    private static readonly nuint SelfInjectionMarker = 0x4B56524D;

    private readonly object _gate = new();
    private readonly HashSet<uint> _pressed = [];
    private readonly HashSet<uint> _suppressedSources = [];
    private readonly Dictionary<uint, uint> _mappedSources = [];
    private readonly Dictionary<uint, int> _mappedTargetReferences = [];
    private readonly HashSet<uint> _injectedTargets = [];
    private readonly LowLevelKeyboardProc _callback;
    private Thread? _thread;
    private nint _hook;
    private uint _threadId;
    private ManualResetEventSlim? _started;

    public WindowsGlobalKeyboardSource()
    {
        _callback = HookCallback;
    }

    public string Id => "windows-global-keyboard";
    public string DisplayName => "Windows 全局键盘（后台监听）";
    public bool IsPassiveWhenUnmodified => true;
    public bool SupportsMapping => true;
    public bool IsRunning { get; private set; }
    public Func<KeyInputEvent, KeyRouteContext>? Router { get; set; }
    public event EventHandler<KeyInputEvent>? Input;

    public void Start()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        lock (_gate)
        {
            if (IsRunning)
            {
                return;
            }

            _started = new ManualResetEventSlim(false);
            _thread = new Thread(MessageLoop)
            {
                IsBackground = true,
                Name = "KeyViewer Windows keyboard hook"
            };
            _thread.Start();
        }

        if (!_started.Wait(TimeSpan.FromSeconds(5)))
        {
            throw new TimeoutException("Windows 键盘钩子启动超时");
        }

        if (_hook == 0)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "无法安装 Windows 键盘钩子");
        }
    }

    public void Stop()
    {
        Thread? thread;
        lock (_gate)
        {
            if (_thread is null)
            {
                return;
            }

            thread = _thread;
            if (_threadId != 0)
            {
                PostThreadMessage(_threadId, WmQuit, 0, 0);
            }
        }

        thread.Join(TimeSpan.FromSeconds(3));
        lock (_gate)
        {
            ReleaseMappedTargets();
            _thread = null;
            _threadId = 0;
            IsRunning = false;
            _pressed.Clear();
            _suppressedSources.Clear();
        }
    }

    private void MessageLoop()
    {
        _threadId = GetCurrentThreadId();
        using var process = Process.GetCurrentProcess();
        using var module = process.MainModule;
        _hook = SetWindowsHookEx(WhKeyboardLl, _callback, GetModuleHandle(module?.ModuleName), 0);
        IsRunning = _hook != 0;
        _started?.Set();
        if (_hook == 0)
        {
            return;
        }

        while (GetMessage(out var message, 0, 0, 0) > 0)
        {
            TranslateMessage(ref message);
            DispatchMessage(ref message);
        }

        UnhookWindowsHookEx(_hook);
        _hook = 0;
        IsRunning = false;
    }

    private nint HookCallback(int code, nuint wParam, nint lParam)
    {
        if (code < 0)
        {
            return CallNextHookEx(_hook, code, wParam, lParam);
        }

        var data = Marshal.PtrToStructure<Kbdllhookstruct>(lParam);
        // Ignore only events injected by our own mapping. Inputs injected by a
        // VM, accessibility software or another remapper still need to pass
        // through KeyViewer so ghost keys and mappings work there as well.
        if (WindowsKeyboardRoutePolicy.ShouldBypassInjectedEvent(
                data.Flags, data.ExtraInfo, SelfInjectionMarker))
        {
            return CallNextHookEx(_hook, code, wParam, lParam);
        }

        var message = unchecked((int)wParam);
        var isDown = message is WmKeyDown or WmSysKeyDown;
        var isUp = message is WmKeyUp or WmSysKeyUp;
        if (!isDown && !isUp)
        {
            return CallNextHookEx(_hook, code, wParam, lParam);
        }

        var state = isUp
            ? KeyEventState.Up
            : _pressed.Add(data.VirtualKey) ? KeyEventState.Down : KeyEventState.Repeat;
        if (isUp)
        {
            _pressed.Remove(data.VirtualKey);
        }

        var keyName = VirtualKeyNames.ToName(data.VirtualKey);
        var input = new KeyInputEvent(keyName, state, "windows:global", DateTimeOffset.UtcNow);
        try
        {
            var router = Router;
            if (router is null)
            {
                Input?.Invoke(this, input);
            }
            else
            {
                var route = router(input);
                var mappingActive = _mappedSources.ContainsKey(data.VirtualKey);
                var decision = WindowsKeyboardRoutePolicy.Decide(route, input.Key, mappingActive);
                if (decision.Action == WindowsKeyboardRouteAction.Map)
                {
                    if (ForwardMapped(data.VirtualKey, decision.TargetVirtualKey, state))
                    {
                        UpdateSuppression(data.VirtualKey, state);
                        return 1;
                    }
                    // If Windows rejects SendInput (for example, a higher-integrity
                    // foreground app), never turn the physical source into a dead key.
                }

                if (state == KeyEventState.Repeat && _suppressedSources.Contains(data.VirtualKey))
                {
                    return 1;
                }
                if (state == KeyEventState.Up && _suppressedSources.Remove(data.VirtualKey))
                {
                    return 1;
                }

                if (decision.Action == WindowsKeyboardRouteAction.Suppress
                    && ShouldSuppress(data.VirtualKey, state))
                {
                    return 1;
                }
            }
        }
        catch
        {
            // A plugin or UI failure must never trap an injected modifier in
            // the down state.  The source-to-target association is recorded on
            // key-down, so key-up can always release it without routing again.
            if (state == KeyEventState.Up && _mappedSources.ContainsKey(data.VirtualKey))
            {
                _ = ForwardMapped(data.VirtualKey, 0, KeyEventState.Up);
                _suppressedSources.Remove(data.VirtualKey);
                return 1;
            }
        }

        return CallNextHookEx(_hook, code, wParam, lParam);
    }

    private bool ShouldSuppress(uint source, KeyEventState state)
    {
        if (state == KeyEventState.Down)
        {
            _suppressedSources.Add(source);
            return true;
        }
        if (state == KeyEventState.Repeat)
        {
            return _suppressedSources.Contains(source);
        }
        return _suppressedSources.Remove(source);
    }

    private void UpdateSuppression(uint source, KeyEventState state)
    {
        if (state == KeyEventState.Down)
        {
            _suppressedSources.Add(source);
        }
        else if (state == KeyEventState.Up)
        {
            _suppressedSources.Remove(source);
        }
    }

    private bool ForwardMapped(uint source, uint target, KeyEventState state)
    {
        if (state == KeyEventState.Down)
        {
            _mappedTargetReferences.TryGetValue(target, out var references);
            if (references == 0 && !SendKey(target, false))
            {
                return false;
            }
            if (references == 0)
            {
                _injectedTargets.Add(target);
            }

            _mappedSources[source] = target;
            _mappedTargetReferences[target] = references + 1;
            return true;
        }
        if (state == KeyEventState.Repeat)
        {
            if (_mappedSources.TryGetValue(source, out var repeatTarget))
            {
                return SendKey(repeatTarget, false);
            }
            return false;
        }
        if (_mappedSources.Remove(source, out var releaseTarget))
        {
            _mappedTargetReferences.TryGetValue(releaseTarget, out var references);
            if (references <= 1)
            {
                _mappedTargetReferences.Remove(releaseTarget);
                if (SendKey(releaseTarget, true))
                {
                    _injectedTargets.Remove(releaseTarget);
                }
            }
            else
            {
                _mappedTargetReferences[releaseTarget] = references - 1;
            }
        }
        return true;
    }

    private void ReleaseMappedTargets()
    {
        foreach (var target in _mappedTargetReferences.Keys.Concat(_injectedTargets).Distinct().ToArray())
        {
            _ = SendKey(target, true);
        }
        _mappedSources.Clear();
        _mappedTargetReferences.Clear();
        _injectedTargets.Clear();
    }

    private static bool SendKey(uint virtualKey, bool isUp)
    {
        var inputs = new[]
        {
            new NativeInput
            {
                Type = InputKeyboard,
                Union = new InputUnion
                {
                    Keyboard = new KeyboardInput
                    {
                        VirtualKey = (ushort)virtualKey,
                        Flags = isUp ? KeyeventfKeyup : 0,
                        ExtraInfo = SelfInjectionMarker
                    }
                }
            }
        };
        return SendInput(1, inputs, Marshal.SizeOf<NativeInput>()) == 1;
    }

    public void Dispose()
    {
        Stop();
        _started?.Dispose();
    }

    private delegate nint LowLevelKeyboardProc(int code, nuint wParam, nint lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct Kbdllhookstruct
    {
        public uint VirtualKey;
        public uint ScanCode;
        public uint Flags;
        public uint Time;
        public nuint ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Message
    {
        public nint Hwnd;
        public uint Value;
        public nuint WParam;
        public nint LParam;
        public uint Time;
        public int PointX;
        public int PointY;
        public uint Private;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeInput
    {
        public uint Type;
        public InputUnion Union;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public KeyboardInput Keyboard;
        // INPUT's native union is sized by MOUSEINPUT (32 bytes on x64), not
        // KEYBDINPUT (24 bytes on x64).  Without this member Marshal.SizeOf
        // reports 32 instead of the required 40 for INPUT and every SendInput
        // call fails with ERROR_INVALID_PARAMETER.
        [FieldOffset(0)] public MouseInput Mouse;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort VirtualKey;
        public ushort ScanCode;
        public uint Flags;
        public uint Time;
        public nuint ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int X;
        public int Y;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public nuint ExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern nint SetWindowsHookEx(int idHook, LowLevelKeyboardProc callback, nint module, uint threadId);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnhookWindowsHookEx(nint hook);
    [DllImport("user32.dll")]
    private static extern nint CallNextHookEx(nint hook, int code, nuint wParam, nint lParam);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern int GetMessage(out Message message, nint window, uint min, uint max);
    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Message message);
    [DllImport("user32.dll")]
    private static extern nint DispatchMessage(ref Message message);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool PostThreadMessage(uint threadId, uint message, nuint wParam, nint lParam);
    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern nint GetModuleHandle(string? moduleName);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, NativeInput[] inputs, int size);
}

public static class VirtualKeyNames
{
    private static readonly Dictionary<uint, string> Names = new()
    {
        [0x08] = "backspace", [0x09] = "tab", [0x0D] = "enter", [0x10] = "shift",
        [0x11] = "ctrl", [0x12] = "alt", [0x13] = "pause", [0x14] = "capslock",
        [0x1B] = "esc", [0x20] = "space", [0x21] = "pageup", [0x22] = "pagedown",
        [0x23] = "end", [0x24] = "home", [0x25] = "left", [0x26] = "up",
        [0x27] = "right", [0x28] = "down", [0x2C] = "printscreen", [0x2D] = "insert",
        [0x2E] = "del", [0x5B] = "lmeta", [0x5C] = "rmeta", [0x60] = "num0",
        [0x61] = "num1", [0x62] = "num2", [0x63] = "num3", [0x64] = "num4",
        [0x65] = "num5", [0x66] = "num6", [0x67] = "num7", [0x68] = "num8",
        [0x69] = "num9", [0x6A] = "asterisk", [0x6B] = "plus", [0x6D] = "minus",
        [0x6E] = "dot", [0x6F] = "slash", [0x90] = "numlock", [0x91] = "scrolllock",
        [0xA0] = "lshift", [0xA1] = "rshift", [0xA2] = "lctrl", [0xA3] = "rctrl",
        [0xA4] = "lalt", [0xA5] = "ralt", [0xBA] = "semicolon", [0xBB] = "equal",
        [0xBC] = "comma", [0xBD] = "minus", [0xBE] = "dot", [0xBF] = "slash",
        [0xC0] = "backquote", [0xDB] = "bracketleft", [0xDC] = "backslash",
        [0xDD] = "bracketright", [0xDE] = "quote"
    };

    private static readonly Dictionary<string, uint> VirtualKeys = Names
        .GroupBy(pair => pair.Value, StringComparer.OrdinalIgnoreCase)
        .ToDictionary(group => group.Key, group => group.First().Key, StringComparer.OrdinalIgnoreCase);

    public static string ToName(uint virtualKey)
    {
        if (virtualKey is >= 0x30 and <= 0x39)
        {
            return ((char)virtualKey).ToString().ToLowerInvariant();
        }

        if (virtualKey is >= 0x41 and <= 0x5A)
        {
            return ((char)virtualKey).ToString().ToLowerInvariant();
        }

        if (virtualKey is >= 0x70 and <= 0x87)
        {
            return $"f{virtualKey - 0x6F}";
        }

        return Names.TryGetValue(virtualKey, out var name) ? name : $"vk_{virtualKey:x2}";
    }

    public static uint ToVirtualKey(string? name)
    {
        name = KeyNames.Normalize(name);
        if (name.Length == 1)
        {
            var character = char.ToUpperInvariant(name[0]);
            if (character is >= 'A' and <= 'Z' or >= '0' and <= '9')
            {
                return character;
            }
        }

        if (name.Length is 2 or 3 && name[0] == 'f'
            && int.TryParse(name[1..], out var function) && function is >= 1 and <= 24)
        {
            return (uint)(0x6F + function);
        }

        return VirtualKeys.TryGetValue(name, out var key) ? key : 0;
    }
}
