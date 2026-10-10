using System.Runtime.InteropServices;
using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using KeyViewer.UI;
using KeyViewer.UI.Controls;

namespace KeyViewer.Desktop;

public sealed class OverlayWindow : Window
{
    private const int GwlStyle = -16;
    private const int GwlExStyle = -20;
    private const long WsThickFrame = 0x00040000L;
    private const long WsMaximizeBox = 0x00010000L;
    private const long WsExToolWindow = 0x00000080L;
    private const long WsExNoActivate = 0x08000000L;
    private const uint SwpNoSize = 0x0001;
    private const uint SwpNoMove = 0x0002;
    private const uint SwpNoZOrder = 0x0004;
    private const uint SwpNoActivate = 0x0010;
    private const uint SwpFrameChanged = 0x0020;
    private const double DragThresholdPixels = 10;

    private readonly KeyViewerAppHost _host;
    private readonly KeyCanvasControl _canvas;
    private readonly DispatcherTimer _liquidGlassTimer;
    private WindowsLiquidGlassCompositionHost? _liquidGlassHost;
    private Point _dragStartScreen;
    private PixelPoint _dragStartWindow;
    private bool _pointerDown;
    private bool _dragging;

    public OverlayWindow(KeyViewerAppHost host)
    {
        _host = host;
        Title = "KeyViewer Overlay";
        WindowDecorations = Avalonia.Controls.WindowDecorations.None;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        CanResize = false;
        Background = Brushes.Transparent;
        TransparencyLevelHint = [WindowTransparencyLevel.Transparent];
        _canvas = new KeyCanvasControl
        {
            Editable = false,
            DrawBackground = false,
            DrawGrid = false
        };
        _canvas.Attach(host);
        _liquidGlassTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(1000d / 60)
        };
        _liquidGlassTimer.Tick += (_, _) => UpdateNativeLiquidGlass();
        Content = _canvas;
        Position = new PixelPoint(
            host.Document.GlobalSettings.FloatWindowX,
            host.Document.GlobalSettings.FloatWindowY);
        AddHandler(PointerPressedEvent, OnPointerPressed, RoutingStrategies.Tunnel);
        AddHandler(PointerMovedEvent, OnPointerMoved, RoutingStrategies.Tunnel);
        AddHandler(PointerReleasedEvent, OnPointerReleased, RoutingStrategies.Tunnel);
        Opened += (_, _) =>
        {
            // Avalonia raises Opened before Win32 has finished showing the
            // native window.  Changing GWL_STYLE synchronously here can write
            // back a pre-show style without WS_VISIBLE, leaving a logically
            // open overlay that never appears.  ShowActivated already avoids
            // the initial focus steal; apply the stronger tool-window/no-
            // activate flags once the native show operation has completed.
            Dispatcher.UIThread.Post(() =>
            {
                ApplyNativeOverlayStyles();
                RefreshLayout();
                InitializeNativeLiquidGlass();
            }, DispatcherPriority.Loaded);
        };
        Closed += (_, _) =>
        {
            _liquidGlassTimer.Stop();
            _liquidGlassHost?.Dispose();
            _liquidGlassHost = null;
            _host.Changed -= OnHostChanged;
            _canvas.Dispose();
            SavePosition();
        };
        _host.Changed += OnHostChanged;
    }

    public void RefreshLayout()
    {
        if (_host.Document.Keys.Count == 0)
        {
            Width = 320;
            Height = 180;
            return;
        }

        var visualBounds = _canvas.GetDocumentVisualBounds();
        var scale = Math.Clamp(_host.Document.GlobalSettings.FloatWindowScale, 0.5, 2);
        var width = Math.Clamp((visualBounds.Width + 40) * scale, 180, 1600);
        var height = Math.Clamp((visualBounds.Height + 40) * scale, 100, 1000);
        MinWidth = 0;
        MaxWidth = double.PositiveInfinity;
        MinHeight = 0;
        MaxHeight = double.PositiveInfinity;
        Width = width;
        Height = height;
        MinWidth = MaxWidth = width;
        MinHeight = MaxHeight = height;
        Dispatcher.UIThread.Post(() => _canvas.FitDocument(12), DispatcherPriority.Render);
    }

    public void ResetPosition()
    {
        _host.Document.GlobalSettings.FloatWindowX = 100;
        _host.Document.GlobalSettings.FloatWindowY = 200;
        Position = new PixelPoint(100, 200);
        _host.NotifyEdited();
    }

    private void OnHostChanged()
        => Dispatcher.UIThread.Post(RefreshLayout, DispatcherPriority.Background);

    private void ApplyNativeOverlayStyles()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var handle = TryGetPlatformHandle()?.Handle ?? 0;
        if (handle == 0)
        {
            return;
        }

        var style = GetWindowLongPointer(handle, GwlStyle).ToInt64();
        SetWindowLongPointer(handle, GwlStyle, new nint(style & ~(WsThickFrame | WsMaximizeBox)));
        var extended = GetWindowLongPointer(handle, GwlExStyle).ToInt64();
        SetWindowLongPointer(handle, GwlExStyle,
            new nint(extended | WsExToolWindow | WsExNoActivate));
        _ = SetWindowPos(handle, 0, 0, 0, 0, 0,
            SwpNoMove | SwpNoSize | SwpNoZOrder | SwpNoActivate | SwpFrameChanged);
    }

    private void InitializeNativeLiquidGlass()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var handle = TryGetPlatformHandle()?.Handle ?? 0;
        if (handle == 0)
        {
            return;
        }

        if (!WindowsLiquidGlassCompositionHost.TryCreate(handle, out _liquidGlassHost, out var error))
        {
            Debug.WriteLine($"Liquid Glass native backdrop unavailable: {error}");
            _canvas.UseNativeLiquidGlassBackdrop = false;
            _canvas.InvalidateVisual();
            return;
        }

        _canvas.UseNativeLiquidGlassBackdrop = true;
        _canvas.InvalidateVisual();
        UpdateNativeLiquidGlass();
        _liquidGlassTimer.Start();
    }

    private void UpdateNativeLiquidGlass()
    {
        if (_liquidGlassHost is null)
        {
            return;
        }

        var scaling = Math.Max(0.01, RenderScaling);
        _liquidGlassHost.Update(
            (float)Math.Max(1, Bounds.Width * scaling),
            (float)Math.Max(1, Bounds.Height * scaling),
            scaling,
            _canvas.GetLiquidGlassVisuals());
    }

    private void OnPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
        {
            return;
        }

        _pointerDown = true;
        _dragging = false;
        _dragStartScreen = PointerScreenPosition(e);
        _dragStartWindow = Position;
        e.Pointer.Capture(this);
        e.Handled = true;
    }

    private void OnPointerMoved(object? sender, PointerEventArgs e)
    {
        if (!_pointerDown)
        {
            return;
        }

        var current = PointerScreenPosition(e);
        var delta = current - _dragStartScreen;
        if (!_dragging && Math.Abs(delta.X) + Math.Abs(delta.Y) >= DragThresholdPixels)
        {
            _dragging = true;
        }

        if (_dragging)
        {
            Position = new PixelPoint(
                _dragStartWindow.X + (int)Math.Round(delta.X),
                _dragStartWindow.Y + (int)Math.Round(delta.Y));
        }
        e.Handled = true;
    }

    private void OnPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (!_pointerDown)
        {
            return;
        }

        _pointerDown = false;
        e.Pointer.Capture(null);
        if (_dragging)
        {
            SavePosition();
        }
        _dragging = false;
        e.Handled = true;
    }

    private Point PointerScreenPosition(PointerEventArgs e)
    {
        var local = e.GetPosition(this);
        return new Point(
            Position.X + local.X * RenderScaling,
            Position.Y + local.Y * RenderScaling);
    }

    private void SavePosition()
    {
        _host.Document.GlobalSettings.FloatWindowX = Position.X;
        _host.Document.GlobalSettings.FloatWindowY = Position.Y;
        _host.NotifyEdited();
    }

    private static nint GetWindowLongPointer(nint window, int index)
        => nint.Size == 8 ? GetWindowLongPtr64(window, index) : new nint(GetWindowLong32(window, index));

    private static void SetWindowLongPointer(nint window, int index, nint value)
    {
        if (nint.Size == 8)
        {
            _ = SetWindowLongPtr64(window, index, value);
        }
        else
        {
            _ = SetWindowLong32(window, index, value.ToInt32());
        }
    }

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong32(nint window, int index);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern nint GetWindowLongPtr64(nint window, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongW", SetLastError = true)]
    private static extern int SetWindowLong32(nint window, int index, int value);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern nint SetWindowLongPtr64(nint window, int index, nint value);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(
        nint window,
        nint insertAfter,
        int x,
        int y,
        int width,
        int height,
        uint flags);
}
