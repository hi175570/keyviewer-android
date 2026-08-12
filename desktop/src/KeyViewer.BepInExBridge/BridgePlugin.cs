using System.IO.Pipes;
using System.Text.Json;
using System.Threading.Channels;
using BepInEx;
using BepInEx.NET.Common;

namespace KeyViewer.BepInExBridge;

[BepInPlugin(PluginGuid, PluginName, PluginVersion)]
public sealed class BridgePlugin : BasePlugin
{
    public const string PluginGuid = "io.keyviewer.bridge";
    public const string PluginName = "KeyViewer Bridge";
    public const string PluginVersion = "1.0.0";

    private BridgeTransport? _transport;

    public override void Load()
    {
        var enabled = Config.Bind("General", "Enabled", true, "向 KeyViewer 桌面端发送事件");
        var pipeName = Config.Bind(
            "General",
            "PipeName",
            "keyviewer.bridge.v1",
            "必须和 KeyViewer 全局设置中的桥接管道名称一致");
        if (!enabled.Value)
        {
            Log.LogInfo("KeyViewer bridge is disabled by configuration.");
            return;
        }

        _transport = new BridgeTransport(pipeName.Value, message => Log.LogWarning(message));
        _transport.Start();
        KeyViewerBridge.Attach(_transport);
        Log.LogInfo($"KeyViewer bridge is ready on pipe '{pipeName.Value}'.");
    }

    public override bool Unload()
    {
        KeyViewerBridge.Detach(_transport);
        _transport?.Dispose();
        _transport = null;
        return true;
    }
}

public enum BridgeKeyState
{
    Down,
    Repeat,
    Up
}

public static class KeyViewerBridge
{
    private static BridgeTransport? _transport;

    internal static void Attach(BridgeTransport transport)
        => Interlocked.Exchange(ref _transport, transport)?.Dispose();

    internal static void Detach(BridgeTransport? transport)
    {
        if (ReferenceEquals(Volatile.Read(ref _transport), transport))
        {
            Interlocked.CompareExchange(ref _transport, null, transport);
        }
    }

    public static bool PublishKey(
        string key,
        BridgeKeyState state,
        string deviceId = "bepinex")
    {
        if (string.IsNullOrWhiteSpace(key))
        {
            return false;
        }

        return Volatile.Read(ref _transport)?.Publish(new BridgeMessage
        {
            Key = key.Trim(),
            State = state.ToString().ToLowerInvariant(),
            DeviceId = deviceId,
            TimestampUnixMilliseconds = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        }) ?? false;
    }
}

internal sealed class BridgeTransport(string pipeName, Action<string> warning) : IDisposable
{
    private readonly Channel<BridgeMessage> _messages = Channel.CreateBounded<BridgeMessage>(
        new BoundedChannelOptions(4096)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });
    private CancellationTokenSource? _cancellation;
    private Task? _worker;

    public void Start()
    {
        if (_worker is not null)
        {
            return;
        }

        _cancellation = new CancellationTokenSource();
        _worker = Task.Run(() => RunAsync(_cancellation.Token));
    }

    public bool Publish(BridgeMessage message) => _messages.Writer.TryWrite(message);

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var pipe = new NamedPipeClientStream(
                    ".",
                    pipeName,
                    PipeDirection.Out,
                    PipeOptions.Asynchronous);
                await pipe.ConnectAsync(1_000, cancellationToken).ConfigureAwait(false);
                await using var writer = new StreamWriter(pipe) { AutoFlush = true };
                while (await _messages.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
                {
                    while (_messages.Reader.TryRead(out var message))
                    {
                        await writer.WriteLineAsync(JsonSerializer.Serialize(message)).ConfigureAwait(false);
                    }
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception) when (exception is IOException or TimeoutException)
            {
                warning($"KeyViewer is not connected: {exception.Message}");
                await Task.Delay(1_000, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    public void Dispose()
    {
        _messages.Writer.TryComplete();
        _cancellation?.Cancel();
        try
        {
            _worker?.Wait(TimeSpan.FromSeconds(2));
        }
        catch (AggregateException exception) when (exception.InnerExceptions.All(
                   inner => inner is OperationCanceledException))
        {
        }
        _cancellation?.Dispose();
    }
}

internal sealed class BridgeMessage
{
    public string Key { get; set; } = string.Empty;
    public string State { get; set; } = string.Empty;
    public string DeviceId { get; set; } = string.Empty;
    public long TimestampUnixMilliseconds { get; set; }
}
