using Com.KV.Bridge;
using KeyViewer.Abstractions;

namespace KeyViewer.Android;

public sealed class AndroidInputSource : IRoutingInputSource
{
    private readonly RouteCallback _routeCallback;
    private readonly AccessibilityCallback _accessibilityCallback;
    private readonly AndroidInputBridge _shizukuBridge;
    private readonly AccessibilityInputBridge _accessibilityBridge;
    private bool _started;
    private bool _disposed;
    private string _mode;

    public AndroidInputSource(string mode)
    {
        _mode = NormalizeMode(mode);
        _routeCallback = new RouteCallback(this);
        _accessibilityCallback = new AccessibilityCallback(this);
        var context = global::Android.App.Application.Context;
        _shizukuBridge = new AndroidInputBridge(context, _routeCallback);
        _accessibilityBridge = new AccessibilityInputBridge(context, _accessibilityCallback);
    }

    public string Id => "android-keyboard";
    public string DisplayName => _mode switch
    {
        "accessibility" => "Android 无障碍键盘（只读）",
        "root" => "Android Root/Sui 键盘（完整）",
        _ => "Android Shizuku 键盘（完整）"
    };
    public bool IsRunning => !_disposed && (_mode == "accessibility"
        ? _accessibilityBridge.IsRunning
        : _shizukuBridge.IsRunning);
    public string Mode => _mode;
    public Func<KeyInputEvent, KeyRouteContext>? Router { get; set; }
    public string Status { get; private set; } = "idle";

    public event EventHandler<KeyInputEvent>? Input;
    public event Action<string>? StatusChanged;

    public void Start()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        _started = true;
        StartSelectedBridge();
    }

    public void Stop()
    {
        if (_disposed) return;
        _started = false;
        _accessibilityBridge.Stop();
        _shizukuBridge.Stop();
    }

    public void SwitchMode(string mode)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        mode = NormalizeMode(mode);
        if (_mode == mode)
        {
            RefreshStatus();
            return;
        }

        _accessibilityBridge.Stop();
        _shizukuBridge.Stop();
        _mode = mode;
        SetStatus("switching:" + mode);
        if (_started) StartSelectedBridge();
    }

    public void OpenCurrentSettings()
    {
        if (_mode == "accessibility") _accessibilityBridge.OpenSettings();
        else _shizukuBridge.RequestPermission();
    }

    public void RefreshStatus()
    {
        if (_mode == "accessibility") _accessibilityBridge.RefreshStatus();
        else if (_started && !_shizukuBridge.IsRunning)
        {
            if (_mode == "root") _shizukuBridge.StartRoot();
            else _shizukuBridge.Start();
        }
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

    private void Observe(string key, int state, string deviceId, long eventTimeMillis)
    {
        var timestamp = DateTimeOffset.UnixEpoch.AddMilliseconds(Math.Max(0, eventTimeMillis));
        var input = new KeyInputEvent(
            key,
            ToState(state),
            deviceId,
            timestamp,
            isInjected: false,
            isObservationOnly: true);

        var router = Router;
        if (router is null) Input?.Invoke(this, input);
        else router(input);
    }

    private void StartSelectedBridge()
    {
        if (_mode == "accessibility") _accessibilityBridge.Start();
        else if (_mode == "root") _shizukuBridge.StartRoot();
        else _shizukuBridge.Start();
    }

    private static KeyEventState ToState(int state) => state switch
    {
        0 => KeyEventState.Down,
        1 => KeyEventState.Repeat,
        _ => KeyEventState.Up
    };

    private static string NormalizeMode(string? mode)
        => mode?.Trim().ToLowerInvariant() switch
        {
            "accessibility" => "accessibility",
            "root" => "root",
            _ => "shizuku"
        };

    private void SetStatus(string status)
    {
        Status = status;
        global::Android.Util.Log.Debug("KV_DotNetInput", status);
        StatusChanged?.Invoke(status);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _accessibilityBridge.Close();
        _accessibilityBridge.Dispose();
        _shizukuBridge.Close();
        _shizukuBridge.Dispose();
        _accessibilityCallback.Dispose();
        _routeCallback.Dispose();
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

    private sealed class AccessibilityCallback : Java.Lang.Object, IAccessibilityInputCallback
    {
        private readonly WeakReference<AndroidInputSource> _owner;

        public AccessibilityCallback(AndroidInputSource owner)
            => _owner = new WeakReference<AndroidInputSource>(owner);

        public void OnInput(string? key, int state, string? deviceId, long eventTimeMillis)
        {
            if (_owner.TryGetTarget(out var owner))
            {
                owner.Observe(
                    key ?? string.Empty,
                    state,
                    deviceId ?? "android:accessibility:unknown",
                    eventTimeMillis);
            }
        }

        public void OnStatus(string? status)
        {
            if (_owner.TryGetTarget(out var owner))
            {
                owner.SetStatus(status ?? "unknown");
            }
        }
    }
}
