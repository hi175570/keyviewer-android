using System.IO.Pipes;
using System.Text.Json;
using KeyViewer.Abstractions;

namespace KeyViewer.Core.Input;

public sealed class BridgePipeInputSource : IInputSource
{
    private readonly string _pipeName;
    private readonly object _gate = new();
    private CancellationTokenSource? _cancellation;
    private Task? _listener;

    public BridgePipeInputSource(string pipeName = "keyviewer.bridge.v1")
    {
        if (string.IsNullOrWhiteSpace(pipeName))
        {
            throw new ArgumentException("管道名称不能为空", nameof(pipeName));
        }

        _pipeName = pipeName;
    }

    public string Id => "bridge-pipe";
    public string DisplayName => $"BepInEx / IPC ({_pipeName})";
    public bool IsRunning { get; private set; }
    public event EventHandler<KeyInputEvent>? Input;

    public void Start()
    {
        lock (_gate)
        {
            if (IsRunning)
            {
                return;
            }

            IsRunning = true;
            _cancellation = new CancellationTokenSource();
            _listener = Task.Run(() => ListenAsync(_cancellation.Token));
        }
    }

    public void Stop()
    {
        Task? listener;
        lock (_gate)
        {
            if (!IsRunning)
            {
                return;
            }

            IsRunning = false;
            _cancellation?.Cancel();
            listener = _listener;
        }

        try
        {
            listener?.Wait(TimeSpan.FromSeconds(2));
        }
        catch (AggregateException exception) when (exception.InnerExceptions.All(
                   inner => inner is OperationCanceledException or IOException))
        {
        }
    }

    private async Task ListenAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var pipe = new NamedPipeServerStream(
                    _pipeName,
                    PipeDirection.In,
                    NamedPipeServerStream.MaxAllowedServerInstances,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous);
                await pipe.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                using var reader = new StreamReader(pipe);
                while (!cancellationToken.IsCancellationRequested && pipe.IsConnected)
                {
                    var line = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
                    if (line is null)
                    {
                        break;
                    }

                    if (TryParse(line, out var input))
                    {
                        Input?.Invoke(this, input);
                    }
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (IOException)
            {
                await Task.Delay(200, cancellationToken).ConfigureAwait(false);
            }
            catch (JsonException)
            {
            }
        }
    }

    public static bool TryParse(string json, out KeyInputEvent input)
    {
        input = null!;
        var message = JsonSerializer.Deserialize<BridgeMessage>(json, JsonOptions);
        if (message is null || string.IsNullOrWhiteSpace(message.Key)
            || !Enum.TryParse<KeyEventState>(message.State, true, out var state))
        {
            return false;
        }

        DateTimeOffset timestamp;
        try
        {
            timestamp = message.TimestampUnixMilliseconds > 0
                ? DateTimeOffset.FromUnixTimeMilliseconds(message.TimestampUnixMilliseconds)
                : DateTimeOffset.UtcNow;
        }
        catch (ArgumentOutOfRangeException)
        {
            timestamp = DateTimeOffset.UtcNow;
        }

        input = new KeyInputEvent(
            KeyNames.Normalize(message.Key),
            state,
            string.IsNullOrWhiteSpace(message.DeviceId) ? "bridge" : message.DeviceId,
            timestamp,
            true);
        return true;
    }

    public void Dispose()
    {
        Stop();
        _cancellation?.Dispose();
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private sealed class BridgeMessage
    {
        public string Key { get; set; } = string.Empty;
        public string State { get; set; } = string.Empty;
        public string DeviceId { get; set; } = string.Empty;
        public long TimestampUnixMilliseconds { get; set; }
    }
}
