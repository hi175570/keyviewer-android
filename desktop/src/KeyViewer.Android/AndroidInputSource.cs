using Com.KV.Bridge;
using KeyViewer.Abstractions;

namespace KeyViewer.Android;

public sealed class AndroidInputSource : IRoutingInputSource
{
    private readonly RouteCallback _callback;
    private readonly AndroidInputBridge _bridge;
    private bool _disposed;

    public AndroidInputSource()
    {
        _callback = new RouteCallback(this);
        _bridge = new AndroidInputBridge(global::Android.App.Application.Context, _callback);
    }

    public string Id => "android-shizuku-keyboard";
    public string DisplayName => "Android Shizuku 键盘";
    public bool IsRunning => !_disposed && _bridge.IsRunning;
    public Func<KeyInputEvent, KeyRouteContext>? Router { get; set; }
    public string Status { get; private set; } = "idle";

    public event EventHandler<KeyInputEvent>? Input;
    public event Action<string>? StatusChanged;

    public void Start()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        _bridge.Start();
    }

    public void Stop()
    {
        if (!_disposed) _bridge.Stop();
    }

    private string Route(string key, int state, string deviceId)
    {
        var input = new KeyInputEvent(
            key,
            state switch
            {
                0 => KeyEventState.Down,
                1 => KeyEventState.Repeat,
                _ => KeyEventState.Up
            },
            deviceId,
            DateTimeOffset.UtcNow);

        var router = Router;
        if (router is null)
        {
            Input?.Invoke(this, input);
            return key;
        }

        var route = router(input);
        return route.ForwardToSystem ? route.OutputKey : string.Empty;
    }

    private void SetStatus(string status)
    {
        Status = status;
        global::Android.Util.Log.Debug("KV_DotNetInput", status);
        StatusChanged?.Invoke(status);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _bridge.Close();
        _bridge.Dispose();
        _callback.Dispose();
        _disposed = true;
    }

    private sealed class RouteCallback : Java.Lang.Object, IAndroidInputCallback
    {
        private readonly WeakReference<AndroidInputSource> _owner;

        public RouteCallback(AndroidInputSource owner)
            => _owner = new WeakReference<AndroidInputSource>(owner);

        public string? Route(string? key, int state, string? deviceId)
            => _owner.TryGetTarget(out var owner)
                ? owner.Route(key ?? string.Empty, state, deviceId ?? "android:unknown")
                : key;

        public void OnStatus(string? status)
        {
            if (_owner.TryGetTarget(out var owner))
            {
                owner.SetStatus(status ?? "unknown");
            }
        }
    }
}
