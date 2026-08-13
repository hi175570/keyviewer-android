using Android.Content;
using Android.Graphics;
using Android.Net;
using Android.Provider;
using Android.Runtime;
using Android.Views;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using KeyViewer.UI;
using KeyViewer.UI.Controls;

namespace KeyViewer.Android;

public sealed class AndroidPlatformServices : IKeyViewerPlatformServices, IInputBackendPlatformServices
{
    private static readonly IReadOnlyList<InputBackendOption> AndroidInputBackends =
    [
        new(
            "accessibility",
            "无障碍（免 Shizuku）",
            "只读实体键盘：支持按键显示、绑定、计数、KPS 和键雨；不拦截、不映射。"),
        new(
            "shizuku",
            "Shizuku（完整功能）",
            "通过 EVIOCGRAB/uinput 支持映射、鬼键、防抖和按键限制。"),
        new(
            "root",
            "Root / Sui（完整功能）",
            "使用 Magisk Sui 提供的 UID 0 后端；无需无线调试配对，仍需安装并授权 Sui。")
    ];

    private readonly KeyViewerAppHost _host;
    private readonly AndroidInputSource _input;
    private readonly Context _context;
    private readonly IWindowManager _windowManager;
    private DraggableOverlayView? _overlayView;
    private KeyCanvasControl? _overlayCanvas;
    private WindowManagerLayoutParams? _layout;
    private int _overlayRenderPending;
    private RenderTargetBitmap? _overlayRenderTarget;
    private WriteableBitmap? _overlayStaging;
    private byte[]? _overlayPixels;
    private Avalonia.PixelSize _overlayPixelSize;

    public AndroidPlatformServices(KeyViewerAppHost host)
    {
        _host = host;
        _context = global::Android.App.Application.Context;
        var windowService = _context.GetSystemService(Context.WindowService)
            ?? throw new InvalidOperationException("Android WindowManager is unavailable.");
        _windowManager = windowService.JavaCast<IWindowManager>();
        _input = new AndroidInputSource(host.Document.GlobalSettings.AndroidInputMode);
        _input.StatusChanged += OnInputStatusChanged;
        host.AddInputSource(_input);
        host.Changed += OnHostChanged;
        Application.DisplayConfigurationChanged += OnDisplayConfigurationChanged;

        if (host.Document.GlobalSettings.FloatWindowEnabled && Settings.CanDrawOverlays(_context))
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (!IsOverlayVisible)
                {
                    ToggleOverlay();
                }
            }, DispatcherPriority.Loaded);
        }
    }

    public bool IsOverlayVisible => _overlayView?.IsAttachedToWindow == true;
    public string PlatformName => "Android";
    public event Action<bool>? OverlayVisibilityChanged;
    public IReadOnlyList<InputBackendOption> InputBackendOptions => AndroidInputBackends;
    public string ActiveInputBackend => _input.Mode;
    public string InputBackendStatus => DescribeInputStatus(_input.Status);
    public bool ActiveInputBackendSupportsRouting => _input.Mode != "accessibility";
    public event Action? InputBackendChanged;

    public void SetInputBackend(string id)
    {
        if (!AndroidInputBackends.Any(option => option.IsEnabled
                && option.Id.Equals(id, StringComparison.OrdinalIgnoreCase)))
        {
            return;
        }

        _input.SwitchMode(id);
        _host.Document.GlobalSettings.AndroidInputMode = _input.Mode;
        _host.NotifyEdited();
        InputBackendChanged?.Invoke();
    }

    public void OpenInputBackendSettings() => _input.OpenCurrentSettings();

    public void RefreshInputBackendStatus() => _input.RefreshStatus();

    public void ToggleOverlay()
    {
        global::Android.Util.Log.Debug("KV_Overlay", $"toggle requested visible={IsOverlayVisible} permission={Settings.CanDrawOverlays(_context)}");
        if (IsOverlayVisible)
        {
            HideOverlay();
            return;
        }

        if (!Settings.CanDrawOverlays(_context))
        {
            var intent = new Intent(
                Settings.ActionManageOverlayPermission,
                global::Android.Net.Uri.Parse("package:" + _context.PackageName));
            intent.AddFlags(ActivityFlags.NewTask);
            _context.StartActivity(intent);
            return;
        }

        EnsureOverlay();
        if (_overlayView is null || _layout is null) return;
        RefreshOverlay();
        RenderOverlay();
        global::Android.Util.Log.Debug("KV_Overlay", $"adding overlay {_layout.Width}x{_layout.Height} at {_layout.X},{_layout.Y}");
        _windowManager.AddView(_overlayView, _layout);
        global::Android.Util.Log.Debug("KV_Overlay", "overlay added");
        OverlayVisibilityChanged?.Invoke(true);
    }

    private void EnsureOverlay()
    {
        if (_overlayView is not null) return;
        _overlayCanvas = new KeyCanvasControl
        {
            Editable = false,
            DrawBackground = false,
            DrawGrid = false
        };
        // The overlay is rendered into a transparent bitmap before Android composites it.
        // LCD subpixel text is invalid once it leaves its original opaque background and
        // produces split/dark glyph edges. Use grayscale AA and pixel-aligned baselines.
        Avalonia.Media.TextOptions.SetTextRenderingMode(
            _overlayCanvas,
            Avalonia.Media.TextRenderingMode.Antialias);
        Avalonia.Media.TextOptions.SetTextHintingMode(
            _overlayCanvas,
            Avalonia.Media.TextHintingMode.Strong);
        Avalonia.Media.TextOptions.SetBaselinePixelAlignment(
            _overlayCanvas,
            Avalonia.Media.BaselinePixelAlignment.Aligned);
        _overlayCanvas.Attach(_host);

        var (width, height) = CalculateOverlaySize();
        _layout = new WindowManagerLayoutParams(
            width,
            height,
            global::Android.OS.Build.VERSION.SdkInt >= global::Android.OS.BuildVersionCodes.O
                ? (WindowManagerTypes)2038 // TYPE_APPLICATION_OVERLAY (API 26+)
                : WindowManagerTypes.Phone,
            OverlayWindowFlags(),
            Format.Translucent)
        {
            Gravity = GravityFlags.Top | GravityFlags.Start,
            X = Math.Max(0, _host.Document.GlobalSettings.FloatWindowX),
            Y = Math.Max(0, _host.Document.GlobalSettings.FloatWindowY)
        };
        ClampPosition();

        _overlayView = new DraggableOverlayView(
            MainActivity.Current ?? _context,
            MoveOverlayBy,
            SaveOverlayPosition);
        _overlayView.SetBackgroundColor(Color.Transparent);
    }

    public void RefreshOverlay()
    {
        if (_layout is null || _overlayCanvas is null) return;
        var (width, height) = CalculateOverlaySize();
        _layout.Width = width;
        _layout.Height = height;
        ClampPosition();
        if (IsOverlayVisible && _overlayView is not null)
        {
            _windowManager.UpdateViewLayout(_overlayView, _layout);
            ScheduleOverlayRender();
        }
    }

    public void SetEditorForeground(bool _)
    {
        // Keep the overlay touchable in both foreground and background so its position can
        // always be adjusted. NOT_TOUCH_MODAL still lets touches outside its bounds pass through.
    }

    public void ResetOverlayPosition()
    {
        if (_layout is null) return;
        _layout.X = 24;
        _layout.Y = 24;
        SaveOverlayPosition();
        if (IsOverlayVisible && _overlayView is not null)
        {
            _windowManager.UpdateViewLayout(_overlayView, _layout);
        }
    }

    private (int Width, int Height) CalculateOverlaySize()
    {
        var bounds = _overlayCanvas?.GetDocumentVisualBounds() ?? new Avalonia.Rect(0, 0, 320, 180);
        var density = _context.Resources?.DisplayMetrics?.Density ?? 1f;
        var scale = Math.Clamp(_host.Document.GlobalSettings.FloatWindowScale, 0.5, 2.0);
        var metrics = _context.Resources?.DisplayMetrics;
        var screenWidth = metrics?.WidthPixels ?? 1920;
        var screenHeight = metrics?.HeightPixels ?? 1080;
        var width = (int)Math.Ceiling((bounds.Width + 40) * scale * density);
        var height = (int)Math.Ceiling((bounds.Height + 40) * scale * density);
        // A sparse or accidentally distant key must not turn the transparent overlay into a
        // full-screen touch shield. Fit oversized layouts into a movable window instead.
        var maximumWidth = Math.Max(180, (int)Math.Floor(screenWidth * 0.72));
        var maximumHeight = Math.Max(100, (int)Math.Floor(screenHeight * 0.72));
        return (
            Math.Clamp(width, 180, maximumWidth),
            Math.Clamp(height, 100, maximumHeight));
    }

    private WindowManagerFlags OverlayWindowFlags()
    {
        return WindowManagerFlags.NotFocusable
            | WindowManagerFlags.NotTouchModal
            | WindowManagerFlags.LayoutInScreen;
    }

    private void ClampPosition()
    {
        if (_layout is null) return;
        var metrics = _context.Resources?.DisplayMetrics;
        var screenWidth = metrics?.WidthPixels ?? 1920;
        var screenHeight = metrics?.HeightPixels ?? 1080;
        _layout.X = Math.Clamp(_layout.X, 0, Math.Max(0, screenWidth - _layout.Width));
        _layout.Y = Math.Clamp(_layout.Y, 0, Math.Max(0, screenHeight - _layout.Height));
    }

    private void SaveOverlayPosition()
    {
        if (_layout is null) return;
        _host.Document.GlobalSettings.FloatWindowX = _layout.X;
        _host.Document.GlobalSettings.FloatWindowY = _layout.Y;
        _host.NotifyEdited();
    }

    private void MoveOverlayBy(int deltaX, int deltaY)
    {
        if (_layout is null || _overlayView is null || !IsOverlayVisible)
        {
            return;
        }
        _layout.X += deltaX;
        _layout.Y += deltaY;
        ClampPosition();
        _windowManager.UpdateViewLayout(_overlayView, _layout);
    }

    private void OnHostChanged()
    {
        if (IsOverlayVisible)
        {
            ScheduleOverlayRender();
        }
    }

    private void OnDisplayConfigurationChanged()
        => Dispatcher.UIThread.Post(RefreshOverlay, DispatcherPriority.Loaded);

    private void ScheduleOverlayRender()
    {
        if (_overlayView is null || _overlayCanvas is null || _layout is null)
        {
            return;
        }
        if (Interlocked.Exchange(ref _overlayRenderPending, 1) != 0)
        {
            return;
        }
        _ = DispatcherTimer.RunOnce(() =>
        {
            Interlocked.Exchange(ref _overlayRenderPending, 0);
            RenderOverlay();
        }, TimeSpan.FromMilliseconds(33), DispatcherPriority.Render);
    }

    private void RenderOverlay()
    {
        if (_overlayView is null || _overlayCanvas is null || _layout is null)
        {
            return;
        }

        var density = _context.Resources?.DisplayMetrics?.Density ?? 1f;
        var pixelSize = new Avalonia.PixelSize(Math.Max(1, _layout.Width), Math.Max(1, _layout.Height));
        var logicalSize = new Avalonia.Size(pixelSize.Width / density, pixelSize.Height / density);
        _overlayCanvas.Measure(logicalSize);
        _overlayCanvas.Arrange(new Avalonia.Rect(logicalSize));
        _overlayCanvas.FitDocument(12);

        EnsureRenderBuffers(pixelSize, density);
        _overlayRenderTarget!.Render(_overlayCanvas);

        using var framebuffer = _overlayStaging!.Lock();
        _overlayRenderTarget.CopyPixels(framebuffer);

        var packedRowBytes = pixelSize.Width * 4;
        var pixels = _overlayPixels!;
        if (framebuffer.RowBytes == packedRowBytes)
        {
            System.Runtime.InteropServices.Marshal.Copy(framebuffer.Address, pixels, 0, pixels.Length);
        }
        else
        {
            for (var row = 0; row < pixelSize.Height; row++)
            {
                System.Runtime.InteropServices.Marshal.Copy(
                    framebuffer.Address + row * framebuffer.RowBytes,
                    pixels,
                    row * packedRowBytes,
                    packedRowBytes);
            }
        }

        _overlayView.SetPixels(pixels, pixelSize.Width, pixelSize.Height);
    }

    private void EnsureRenderBuffers(Avalonia.PixelSize pixelSize, float density)
    {
        if (_overlayRenderTarget is not null && _overlayPixelSize == pixelSize)
        {
            return;
        }

        _overlayRenderTarget?.Dispose();
        _overlayStaging?.Dispose();
        _overlayPixelSize = pixelSize;
        var dpi = new Avalonia.Vector(96 * density, 96 * density);
        _overlayRenderTarget = new RenderTargetBitmap(pixelSize, dpi);
        _overlayStaging = new WriteableBitmap(
            pixelSize,
            dpi,
            Avalonia.Platform.PixelFormat.Rgba8888,
            AlphaFormat.Premul);
        _overlayPixels = new byte[pixelSize.Width * pixelSize.Height * 4];
    }

    private void HideOverlay()
    {
        if (_overlayView is null || !IsOverlayVisible) return;
        SaveOverlayPosition();
        _windowManager.RemoveView(_overlayView);
        OverlayVisibilityChanged?.Invoke(false);
    }

    public void Dispose()
    {
        _host.Changed -= OnHostChanged;
        Application.DisplayConfigurationChanged -= OnDisplayConfigurationChanged;
        HideOverlay();
        _overlayCanvas?.Dispose();
        _overlayCanvas = null;
        _overlayRenderTarget?.Dispose();
        _overlayRenderTarget = null;
        _overlayStaging?.Dispose();
        _overlayStaging = null;
        _overlayPixels = null;
        if (_overlayView is not null)
        {
            _overlayView.SetPixels(null, 0, 0);
            _overlayView.Dispose();
            _overlayView = null;
        }
        _input.StatusChanged -= OnInputStatusChanged;
        _input.Dispose();
    }

    private void OnInputStatusChanged(string status) => InputBackendChanged?.Invoke();

    private static string DescribeInputStatus(string status)
    {
        if (status.StartsWith("listening:", StringComparison.Ordinal))
        {
            return $"Shizuku 正在监听 {status["listening:".Length..]} 个键盘设备";
        }
        if (status.StartsWith("shizuku_error:", StringComparison.Ordinal))
        {
            return "Shizuku 错误：" + status["shizuku_error:".Length..];
        }
        return status switch
        {
            "accessibility_listening" => "无障碍键盘监听中（事件原样透传给系统）",
            "accessibility_permission_required" => "尚未开启 KeyViewer 无障碍服务",
            "accessibility_waiting" => "无障碍服务已开启，正在等待系统连接",
            "accessibility_disconnected" => "无障碍服务已断开",
            "shizuku_unavailable" => "Shizuku 未启动",
            "root_backend_unavailable" => "没有发现 Root/Sui 后端；请先安装并启用 Magisk Sui",
            "root_backend_required" => "当前连接不是 UID 0；Root 模式需要 Sui 或以 Root 启动的 Shizuku",
            "permission_required" => "需要授予 Shizuku 权限",
            "permission_denied" => "Shizuku 权限被拒绝",
            "starting" => "正在发现实体键盘",
            "no_keyboard" => "没有发现可监听的实体键盘",
            "native_helper_failed" => "Android 原生输入辅助程序启动失败",
            "stopped" => "输入监听已停止",
            var value when value.StartsWith("switching:", StringComparison.Ordinal) => "正在切换输入模式",
            _ => status
        };
    }

    private sealed class DraggableOverlayView : View
    {
        private const float DragThreshold = 10;
        private readonly Action<int, int> _moveBy;
        private readonly Action _positionChanged;
        private float _lastX;
        private float _lastY;
        private bool _dragging;
        private global::Android.Graphics.Bitmap? _bitmap;

        public DraggableOverlayView(
            Context context,
            Action<int, int> moveBy,
            Action positionChanged)
            : base(context)
        {
            _moveBy = moveBy;
            _positionChanged = positionChanged;
        }

        public void SetPixels(byte[]? pixels, int width, int height)
        {
            if (pixels is null)
            {
                _bitmap?.Dispose();
                _bitmap = null;
                Invalidate();
                return;
            }
            if (_bitmap is null || _bitmap.Width != width || _bitmap.Height != height)
            {
                _bitmap?.Dispose();
                _bitmap = global::Android.Graphics.Bitmap.CreateBitmap(
                    width,
                    height,
                    global::Android.Graphics.Bitmap.Config.Argb8888!);
            }
            using var buffer = Java.Nio.ByteBuffer.Wrap(pixels);
            _bitmap.CopyPixelsFromBuffer(buffer);
            Invalidate();
        }

        protected override void OnDraw(global::Android.Graphics.Canvas canvas)
        {
            base.OnDraw(canvas);
            if (_bitmap is not null)
            {
                canvas.DrawBitmap(_bitmap, 0, 0, null);
            }
        }

        public override bool DispatchTouchEvent(MotionEvent? e)
        {
            if (e is null) return false;
            switch (e.ActionMasked)
            {
                case MotionEventActions.Down:
                    _lastX = e.RawX;
                    _lastY = e.RawY;
                    _dragging = false;
                    return true;
                case MotionEventActions.Move:
                    var dx = e.RawX - _lastX;
                    var dy = e.RawY - _lastY;
                    if (!_dragging && Math.Abs(dx) + Math.Abs(dy) >= DragThreshold)
                    {
                        _dragging = true;
                    }
                    if (_dragging)
                    {
                        _moveBy((int)Math.Round(dx), (int)Math.Round(dy));
                    }
                    _lastX = e.RawX;
                    _lastY = e.RawY;
                    return true;
                case MotionEventActions.Up:
                case MotionEventActions.Cancel:
                    if (_dragging) _positionChanged();
                    _dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
