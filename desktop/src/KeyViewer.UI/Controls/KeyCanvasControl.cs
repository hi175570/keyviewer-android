using System.Globalization;
using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Fonts;
using Avalonia.Media.Imaging;
using Avalonia.Rendering;
using Avalonia.Threading;
using KeyViewer.Abstractions;
using KeyViewer.Core.Models;
using KeyViewer.UI.Rendering;

namespace KeyViewer.UI.Controls;

public sealed class KeyCanvasControl : Control, IDisposable
{
    private const double GridSize = KeyViewerLayoutGrid.Size;
    private const double SelectionPadding = 15;
    private const double MultiSelectionPadding = 5;
    private const double HandleSize = 12;
    private static readonly FontFamily DefaultKeyViewerFont = new("fonts:KeyViewer#Maplestory OTF");
    private static readonly FontFamily AdoFaiFont = new("fonts:KeyViewer#godoMaum");
    private static readonly Dictionary<string, string> DisplayNames = new(StringComparer.OrdinalIgnoreCase)
    {
        ["tab"] = "↹", ["backspace"] = "back", ["equal"] = "=", ["minus"] = "-",
        ["backslash"] = "\\", ["slash"] = "/", ["dot"] = ".", ["comma"] = ",",
        ["semicolon"] = ";", ["quote"] = "'", ["backquote"] = "~",
        ["bracketleft"] = "[", ["bracketright"] = "]",
        ["lshift"] = "L⇧", ["rshift"] = "R⇧", ["lctrl"] = "L Ctrl", ["rctrl"] = "R Ctrl",
        ["lalt"] = "L Alt", ["ralt"] = "R Alt", ["space"] = "⎵", ["enter"] = "⏎",
        ["capslock"] = "⇪", ["del"] = "del", ["pageup"] = "pg⇑", ["pagedown"] = "pg⇓",
        ["home"] = "home", ["end"] = "end", ["up"] = "↑", ["down"] = "↓",
        ["left"] = "←", ["right"] = "→", ["esc"] = "Esc", ["lmeta"] = "Win", ["rmeta"] = "Win"
    };
    private readonly Dictionary<string, Bitmap?> _images = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, FontFamily?> _fonts = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, EmbeddedFontCollection> _fontCollections = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, bool> _previousPressed = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<RainParticle> _rain = [];
    private readonly List<KeyDefinition> _selectedKeys = [];
    private readonly List<KeyDefinition> _clipboard = [];
    private readonly List<KeyDefinition> _pastePreview = [];
    private readonly DispatcherTimer _timer;
    private KeyViewerAppHost? _host;
    private KeyDefinition? _selected;
    private Point _lastPointer;
    private bool _draggingKey;
    private bool _panning;
    private ResizeEdge _resizeEdge;
    private bool _pointerMoved;
    private bool _removeBatchSelectionOnClick;
    private bool _touchGesture;
    private long _lastTouchRenderTimestamp;

    public KeyCanvasControl()
    {
        ClipToBounds = true;
        Focusable = true;
        _timer = new DispatcherTimer(TimeSpan.FromMilliseconds(16), DispatcherPriority.Background, OnFrame);
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        ProcessPointerPressed(e, this);
    }

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        base.OnPointerMoved(e);
        ProcessPointerMoved(e);
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        ProcessPointerReleased(e);
    }

    protected override void OnPointerWheelChanged(PointerWheelEventArgs e)
    {
        base.OnPointerWheelChanged(e);
        ProcessPointerWheelChanged(e);
    }

    public bool Editable { get; set; } = true;
    public bool DrawBackground { get; set; } = true;
    public bool DrawGrid { get; set; } = true;
    public bool UseNativeLiquidGlassBackdrop { get; set; }
    public double Zoom { get; private set; } = 1;
    public Vector Pan { get; private set; }
    public Point ViewCenterWorld => ScreenToWorld(Bounds.Center);
    public bool BatchEditMode { get; private set; }
    public bool IsPasting => _pastePreview.Count > 0;
    public bool CanPaste => _clipboard.Count > 0;
    public IReadOnlyList<KeyDefinition> SelectedKeys => _selectedKeys;
    public KeyDefinition? SelectionBaseline => _selected;

    public void SelectAllKeys()
    {
        if (_host is null || !BatchEditMode || _selected is null || !_selectedKeys.Contains(_selected))
        {
            return;
        }

        var baseline = _selected;
        ReplaceSelection(
            new[] { baseline }.Concat(_host.Document.Keys.Where(key => !ReferenceEquals(key, baseline))),
            baseline);
    }

    public KeyDefinition? SelectedKey
    {
        get => _selected;
        set
        {
            if (ReferenceEquals(_selected, value) && _selectedKeys.Count == (value is null ? 0 : 1))
            {
                return;
            }

            SetSingleSelection(value);
        }
    }

    public event Action<KeyDefinition?>? SelectedKeyChanged;
    public event Action? SelectionChanged;
    public event Action? EditModeChanged;

    public IReadOnlyList<LiquidGlassVisualGeometry> GetLiquidGlassVisuals()
    {
        if (_host is null
            || !UseNativeLiquidGlassBackdrop
            || !_host.Document.GlobalSettings.LiquidGlassEnabled)
        {
            return Array.Empty<LiquidGlassVisualGeometry>();
        }

        var visuals = new List<LiquidGlassVisualGeometry>(_host.Document.Keys.Count);
        foreach (var key in _host.Document.Keys.OrderBy(key => key.ZIndex))
        {
            var pressed = _host.State.IsPressed(key.BoundKey);
            var fillColor = pressed ? key.FillColorDown : key.FillColorUp;
            var borderColor = pressed ? key.BorderColorDown : key.BorderColorUp;
            var surfaceContext = new KeySurfaceContext(
                key.Id,
                pressed,
                _host.Document.GlobalSettings.DarkModeEnabled,
                key.Width,
                key.Height,
                key.CornerRadiusDp,
                fillColor,
                borderColor);
            if (!_host.SurfaceEffects.TryCreateStyle(surfaceContext, out var style))
            {
                continue;
            }

            var rect = WorldRectToScreen(key);
            var inset = pressed ? Math.Max(0, style.PressInset * Zoom) : 0;
            var surface = inset > 0 && rect.Width > inset * 2 && rect.Height > inset * 2
                ? rect.Deflate(inset)
                : rect;
            var radius = Math.Min(
                Math.Max(0, key.CornerRadiusDp * Zoom + style.CornerRadiusBoost * Zoom),
                Math.Min(surface.Width, surface.Height) / 2);
            visuals.Add(new LiquidGlassVisualGeometry(key.Id, surface, radius, style, pressed));
        }

        return visuals;
    }

    public void Attach(KeyViewerAppHost host)
    {
        if (ReferenceEquals(_host, host))
        {
            return;
        }

        if (_host is not null)
        {
            _host.Changed -= OnHostChanged;
        }

        _host = host;
        _host.Changed += OnHostChanged;
        _images.Clear();
        CapturePressedTransitions();
        InvalidateVisual();
    }

    public void ZoomIn() => SetZoom(Zoom * 1.2, Bounds.Center);
    public void ZoomOut() => SetZoom(Zoom / 1.2, Bounds.Center);
    public Point SnapToGrid(Point point) => new(Snap(point.X), Snap(point.Y));

    public void SetBatchEditMode(bool enabled)
    {
        if (BatchEditMode == enabled && !IsPasting)
        {
            return;
        }

        CancelPaste();
        BatchEditMode = enabled;
        ClearSelection();
        EditModeChanged?.Invoke();
    }

    public bool CopySelection()
    {
        if (_selectedKeys.Count == 0)
        {
            return false;
        }

        _clipboard.Clear();
        _clipboard.AddRange(_selectedKeys.Select(key => key.Clone()));
        EditModeChanged?.Invoke();
        return true;
    }

    public bool StartPaste()
    {
        if (_clipboard.Count == 0)
        {
            return false;
        }

        _pastePreview.Clear();
        _pastePreview.AddRange(_clipboard.Select(key => key.Clone()));
        var sourceCenterX = _pastePreview.Average(key => key.CenterX);
        var sourceCenterY = _pastePreview.Average(key => key.CenterY);
        var center = ViewCenterWorld;
        var offsetX = center.X - sourceCenterX;
        var offsetY = center.Y - sourceCenterY;
        foreach (var key in _pastePreview)
        {
            key.CenterX = Snap(key.CenterX + offsetX);
            key.CenterY = Snap(key.CenterY + offsetY);
        }

        ClearSelection();
        EditModeChanged?.Invoke();
        InvalidateVisual();
        return true;
    }

    public bool ConfirmPaste()
    {
        if (_host is null || _pastePreview.Count == 0)
        {
            return false;
        }

        _host.Document.Keys.AddRange(_pastePreview);
        _pastePreview.Clear();
        ClearSelection();
        _host.NotifyEdited();
        EditModeChanged?.Invoke();
        return true;
    }

    public void CancelPaste()
    {
        if (_pastePreview.Count == 0)
        {
            return;
        }

        _pastePreview.Clear();
        EditModeChanged?.Invoke();
        InvalidateVisual();
    }

    public bool DeleteSelection()
    {
        if (_host is null || _selectedKeys.Count == 0)
        {
            return false;
        }

        foreach (var key in _selectedKeys.ToArray())
        {
            _host.Document.Keys.Remove(key);
        }
        ClearSelection();
        _host.NotifyEdited();
        return true;
    }

    public void ResetViewport()
    {
        Zoom = 1;
        Pan = default;
        InvalidateVisual();
    }

    public void SetViewport(double zoom, Vector pan)
    {
        Zoom = Math.Clamp(zoom, 0.2, 5);
        Pan = pan;
        InvalidateVisual();
    }

    public void FitDocument(double padding = 24)
    {
        if (_host is null || _host.Document.Keys.Count == 0 || Bounds.Width <= 0 || Bounds.Height <= 0)
        {
            return;
        }

        var visualBounds = GetDocumentVisualBounds();
        var width = Math.Max(1, visualBounds.Width);
        var height = Math.Max(1, visualBounds.Height);
        Zoom = Math.Clamp(Math.Min(
            (Bounds.Width - padding * 2) / width,
            (Bounds.Height - padding * 2) / height), 0.2, 5);
        Pan = new Vector(
            padding - visualBounds.X * Zoom,
            padding - visualBounds.Y * Zoom);
        InvalidateVisual();
    }

    public Rect GetDocumentVisualBounds()
    {
        if (_host is null || _host.Document.Keys.Count == 0)
        {
            return new Rect(0, 0, 320, 180);
        }

        var minX = _host.Document.Keys.Min(key => key.CenterX - key.Width / 2);
        var minY = _host.Document.Keys.Min(key => key.CenterY - key.Height / 2)
            - Math.Max(0, _host.Document.GlobalSettings.GlobalRainHeightDp);
        var maxX = _host.Document.Keys.Max(key => key.CenterX + key.Width / 2);
        var maxY = _host.Document.Keys.Max(key => key.CenterY + key.Height / 2);

        return new Rect(minX, minY, Math.Max(1, maxX - minX), Math.Max(1, maxY - minY));
    }

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        if (_host is null)
        {
            return;
        }

        DrawCanvasBackground(context);
        if (DrawBackground && DrawGrid)
        {
            DrawGridLines(context);
        }

        foreach (var key in _host.Document.Keys.OrderBy(key => key.ZIndex))
        {
            DrawRain(context, key.Id);
            DrawKey(context, key);
        }

        foreach (var key in _pastePreview.OrderBy(key => key.ZIndex))
        {
            DrawKey(context, key);
            DrawSelection(context, key, false);
        }

        if (Editable)
        {
            foreach (var key in _selectedKeys)
            {
                DrawSelection(context, key, !BatchEditMode && _selectedKeys.Count == 1);
            }
        }
    }

    internal void ProcessPointerPressed(PointerPressedEventArgs e, IInputElement captureTarget)
    {
        if (!Editable || _host is null)
        {
            return;
        }

        Focus();
        _lastPointer = e.GetPosition(this);
        _pointerMoved = false;
        _removeBatchSelectionOnClick = false;
        var properties = e.GetCurrentPoint(this).Properties;
        _touchGesture = e.Pointer.Type == PointerType.Touch
            || (!properties.IsLeftButtonPressed
                && !properties.IsMiddleButtonPressed
                && !properties.IsRightButtonPressed);
        if (properties.IsMiddleButtonPressed || properties.IsRightButtonPressed)
        {
            _panning = true;
        }
        else
        {
            var world = ScreenToWorld(_lastPointer);
            if (IsPasting)
            {
                _draggingKey = true;
                e.Pointer.Capture(captureTarget);
                e.Handled = true;
                return;
            }

            var hit = HitTestKey(world);
            if (BatchEditMode)
            {
                if (hit is null)
                {
                    _draggingKey = false;
                    _panning = true;
                }
                else if (_selectedKeys.Contains(hit))
                {
                    AddSelection(hit);
                    _draggingKey = true;
                    _removeBatchSelectionOnClick = true;
                }
                else
                {
                    AddSelection(hit);
                    _draggingKey = false;
                }
            }
            else
            {
                SelectedKey = hit;
                if (hit is not null)
                {
                    BringToFront(hit);
                    _resizeEdge = HitResizeHandle(_lastPointer, hit);
                    _draggingKey = _resizeEdge == ResizeEdge.None;
                }
                else
                {
                    _panning = true;
                }
            }
        }

        e.Pointer.Capture(captureTarget);
        e.Handled = true;
    }

    internal void ProcessPointerMoved(PointerEventArgs e)
    {
        if (_host is null || (!_draggingKey && !_panning && _resizeEdge == ResizeEdge.None))
        {
            return;
        }

        var point = e.GetPosition(this);
        var delta = point - _lastPointer;
        if (Math.Abs(delta.X) + Math.Abs(delta.Y) > 0.5)
        {
            _pointerMoved = true;
        }
        _lastPointer = point;
        if (_panning)
        {
            Pan += delta;
        }
        else if (_resizeEdge != ResizeEdge.None && SelectedKey is not null)
        {
            ResizeSelected(delta / Zoom);
        }
        else if (_draggingKey)
        {
            var targets = IsPasting ? _pastePreview : _selectedKeys;
            if (BatchEditMode && !IsPasting)
            {
                MoveBatchSelection(delta / Zoom);
            }
            else
            {
                foreach (var key in targets)
                {
                    key.CenterX += delta.X / Zoom;
                    key.CenterY += delta.Y / Zoom;
                }

                if (!IsPasting)
                {
                    ConstrainKeysToViewport(targets, VisibleWorldBounds());
                }
            }
        }

        InvalidateAfterPointerMove();
        e.Handled = true;
    }

    internal void ProcessPointerReleased(PointerReleasedEventArgs e)
    {
        if ((_draggingKey || _resizeEdge != ResizeEdge.None) && _pointerMoved)
        {
            var targets = IsPasting ? _pastePreview : _selectedKeys;
            foreach (var key in targets)
            {
                key.CenterX = Snap(key.CenterX);
                key.CenterY = Snap(key.CenterY);
            }

            ConstrainKeysToViewport(targets, VisibleWorldBounds());
        }

        if (_removeBatchSelectionOnClick && !_pointerMoved && SelectedKey is not null)
        {
            RemoveSelection(SelectedKey);
        }
        else if ((_draggingKey || _resizeEdge != ResizeEdge.None) && _pointerMoved && !IsPasting)
        {
            _host?.NotifyEdited();
        }

        _draggingKey = false;
        _panning = false;
        _resizeEdge = ResizeEdge.None;
        _removeBatchSelectionOnClick = false;
        _touchGesture = false;
        _lastTouchRenderTimestamp = 0;
        e.Pointer.Capture(null);
        InvalidateVisual();
        e.Handled = true;
    }

    internal void ProcessPointerWheelChanged(PointerWheelEventArgs e)
    {
        if (!Editable)
        {
            e.Handled = true;
            return;
        }

        var factor = e.Delta.Y > 0 ? 1.12 : 1 / 1.12;
        SetZoom(Zoom * factor, e.GetPosition(this));
        e.Handled = true;
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (!Editable || _selectedKeys.Count == 0 || _host is null || IsPasting)
        {
            return;
        }

        var step = e.KeyModifiers.HasFlag(KeyModifiers.Shift) ? GridSize : 1;
        switch (e.Key)
        {
            case Key.Left:
                foreach (var key in _selectedKeys) key.CenterX -= step;
                break;
            case Key.Right:
                foreach (var key in _selectedKeys) key.CenterX += step;
                break;
            case Key.Up:
                foreach (var key in _selectedKeys) key.CenterY -= step;
                break;
            case Key.Down:
                foreach (var key in _selectedKeys) key.CenterY += step;
                break;
            case Key.Delete:
                DeleteSelection();
                e.Handled = true;
                return;
            default:
                return;
        }

        _host.NotifyEdited();
        e.Handled = true;
    }

    private void DrawCanvasBackground(DrawingContext context)
    {
        if (!DrawBackground)
        {
            return;
        }

        var dark = _host?.Document.GlobalSettings.DarkModeEnabled == true;
        context.FillRectangle(new SolidColorBrush(dark ? Colors.Black : Color.Parse("#E0E0E0")), Bounds);
        var name = _host?.Document.GlobalSettings.BackgroundImageAsset;
        var bitmap = ResolveBitmap(name);
        if (bitmap is null || Bounds.Width <= 0 || Bounds.Height <= 0)
        {
            return;
        }

        var source = CoverSource(bitmap.Size.Width, bitmap.Size.Height, Bounds.Width, Bounds.Height);
        context.DrawImage(bitmap, source, Bounds);
    }

    private void DrawGridLines(DrawingContext context)
    {
        var pen = new Pen(new SolidColorBrush(Color.Parse("#A0A0A0")), 1);
        var spacing = GridSize * Zoom;
        if (spacing < 5)
        {
            return;
        }

        var startX = ((Pan.X % spacing) + spacing) % spacing;
        var startY = ((Pan.Y % spacing) + spacing) % spacing;
        var geometry = new StreamGeometry();
        using var geometryContext = geometry.Open();
        for (var x = startX; x <= Bounds.Width; x += spacing)
        {
            geometryContext.BeginFigure(new Point(x, 0), false);
            geometryContext.LineTo(new Point(x, Bounds.Height));
            geometryContext.EndFigure(false);
        }

        for (var y = startY; y <= Bounds.Height; y += spacing)
        {
            geometryContext.BeginFigure(new Point(0, y), false);
            geometryContext.LineTo(new Point(Bounds.Width, y));
            geometryContext.EndFigure(false);
        }
        context.DrawGeometry(null, pen, geometry);
    }

    private void DrawKey(DrawingContext context, KeyDefinition key)
    {
        if (_host is null)
        {
            return;
        }

        var pressed = _host.State.IsPressed(key.BoundKey);
        var rect = WorldRectToScreen(key);
        var radius = key.CornerRadiusDp * Zoom;
        var fillColor = pressed ? key.FillColorDown : key.FillColorUp;
        var borderColor = pressed ? key.BorderColorDown : key.BorderColorUp;
        var glassRendered = false;
        if (_host.Document.GlobalSettings.LiquidGlassEnabled && UseNativeLiquidGlassBackdrop)
        {
            var surfaceContext = new KeySurfaceContext(
                key.Id,
                pressed,
                _host.Document.GlobalSettings.DarkModeEnabled,
                key.Width,
                key.Height,
                key.CornerRadiusDp,
                fillColor,
                borderColor);
            if (_host.SurfaceEffects.TryCreateStyle(surfaceContext, out var style))
            {
                DrawLiquidGlassSurface(context, rect, radius, key.BorderWidthDp * Zoom, style, pressed);
                glassRendered = true;
            }
        }

        if (!glassRendered)
        {
            context.DrawRectangle(new SolidColorBrush(ToColor(fillColor)), null, rect, radius, radius);
        }

        var image = ResolveBitmap(key.ImageAsset);
        var imageDrawn = false;
        if (image is not null)
        {
            var inset = Math.Max(2 * Zoom, (key.BorderWidthDp + 1) * Zoom);
            var target = rect.Deflate(inset);
            if (key.ShowCount)
            {
                target = new Rect(target.X, target.Y, target.Width,
                    Math.Max(1, target.Height - key.TextSizeSp * Zoom * 1.25));
            }
            var source = FitSource(image.Size.Width, image.Size.Height, target.Width, target.Height);
            context.DrawImage(image, source, target);
            imageDrawn = true;
        }

        if (!glassRendered)
        {
            var border = new Pen(
                new SolidColorBrush(ToColor(borderColor)),
                Math.Max(0.5, key.BorderWidthDp * Zoom));
            context.DrawRectangle(null, border, rect, radius, radius);
        }

        if (!imageDrawn || !key.ImageReplacesLabel)
        {
            DrawKeyText(context, key, rect, pressed);
        }
        else if (key.ShowCount)
        {
            var countSize = key.CountTextSizeSp ?? key.TextSizeSp;
            DrawCenteredText(
                context,
                _host.State.GetCount(key.BoundKey).ToString(CultureInfo.InvariantCulture),
                new Rect(rect.X, rect.Bottom - countSize * Zoom * 1.3, rect.Width, countSize * Zoom * 1.3),
                key,
                Math.Max(6, countSize * Zoom * 0.8),
                ToColor(pressed ? key.CountColorDown : key.CountColorUp),
                key.CountOffsetXDp * Zoom,
                key.CountOffsetYDp * Zoom,
                fontFamily: FontFor(key));
        }
    }

    private void DrawLiquidGlassSurface(
        DrawingContext context,
        Rect rect,
        double configuredRadius,
        double configuredBorderWidth,
        KeySurfaceStyle style,
        bool pressed)
    {
        _ = configuredBorderWidth;
        var inset = pressed ? Math.Max(0, style.PressInset * Zoom) : 0;
        var surface = inset > 0 && rect.Width > inset * 2 && rect.Height > inset * 2
            ? rect.Deflate(inset)
            : rect;
        var radius = Math.Min(
            Math.Max(0, configuredRadius + style.CornerRadiusBoost * Zoom),
            Math.Min(surface.Width, surface.Height) / 2);
        context.Custom(new LiquidGlassChromeDrawOperation(surface, radius, style));
    }

    private void DrawKeyText(DrawingContext context, KeyDefinition key, Rect rect, bool pressed)
    {
        if (_host is null)
        {
            return;
        }

        var unbound = string.IsNullOrWhiteSpace(key.BoundKey) || key.BoundKey.Equals("未绑定", StringComparison.OrdinalIgnoreCase);
        var label = string.IsNullOrWhiteSpace(key.DisplayLabel)
            ? unbound ? string.Empty
            : DisplayNames.TryGetValue(KeyViewer.Core.KeyNames.Normalize(key.BoundKey), out var displayName)
                ? displayName
                : key.BoundKey.Length == 1 ? key.BoundKey.ToUpperInvariant() : key.BoundKey.ToUpperInvariant()
            : key.DisplayLabel;
        var value = string.Empty;
        if (key.DisplayLabel.Equals("kps", StringComparison.OrdinalIgnoreCase))
        {
            label = ApplyCounterCase("kps", key.CounterLabelCase);
            value = _host.State.Kps.ToString(CultureInfo.InvariantCulture);
        }
        else if (key.DisplayLabel.Equals("total", StringComparison.OrdinalIgnoreCase))
        {
            label = ApplyCounterCase("total", key.CounterLabelCase);
            value = _host.Document.TotalPressCount.ToString(CultureInfo.InvariantCulture);
        }
        else if (key.ShowCount && !unbound)
        {
            value = _host.State.GetCount(key.BoundKey).ToString(CultureInfo.InvariantCulture);
        }

        var textColor = ToColor(pressed ? key.TextColorDown : key.TextColorUp);
        var countColor = ToColor(pressed ? key.CountColorDown : key.CountColorUp);
        var size = Math.Max(6, key.TextSizeSp * Zoom);
        var countSize = Math.Max(6, (key.CountTextSizeSp ?? key.TextSizeSp) * Zoom);
        if (value.Length == 0)
        {
            DrawCenteredText(context, label, rect, key, size, textColor,
                key.TextOffsetXDp * Zoom, key.TextOffsetYDp * Zoom, FontFor(key));
            return;
        }

        if (key.KpsTotalNoWrap && key.IsCounter)
        {
            DrawCenteredText(context, label, rect, key, size, textColor,
                key.TextOffsetXDp * Zoom, key.TextOffsetYDp * Zoom, FontFor(key));
            DrawCenteredText(context, value, rect, key, countSize * DigitScale(value.Length), countColor,
                key.CountOffsetXDp * Zoom, key.CountOffsetYDp * Zoom, FontFor(key), rightAnchored: true);
        }
        else
        {
            DrawCenteredText(context, label, new Rect(rect.X, rect.Y + rect.Height * 0.12, rect.Width, rect.Height * 0.44), key, size, textColor,
                key.TextOffsetXDp * Zoom, key.TextOffsetYDp * Zoom, FontFor(key));
            DrawCenteredText(context, value, new Rect(rect.X, rect.Y + rect.Height * 0.5, rect.Width, rect.Height * 0.5), key, countSize * DigitScale(value.Length), countColor,
                key.CountOffsetXDp * Zoom, key.CountOffsetYDp * Zoom, FontFor(key));
        }
    }

    private static string ApplyCounterCase(string label, string mode) => mode switch
    {
        "lower" => label.ToLowerInvariant(),
        "title" => char.ToUpperInvariant(label[0]) + label[1..].ToLowerInvariant(),
        _ => label.ToUpperInvariant()
    };

    private void DrawCenteredText(
        DrawingContext context,
        string text,
        Rect rect,
        KeyDefinition key,
        double size,
        Color color,
        double offsetX = 0,
        double offsetY = 0,
        FontFamily? fontFamily = null,
        bool rightAnchored = false)
    {
        fontFamily ??= FontFamily.Default;
        var typeface = new Typeface(fontFamily, FontStyle.Normal, FontWeight.Bold);
        var formatted = new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            typeface,
            size,
            new SolidColorBrush(color));
        var shadowExtra = key.TextShadowEnabled
            ? (Math.Abs(key.TextShadowOffsetXDp) + key.TextShadowBlurRadiusDp) * Zoom
            : 0;
        var horizontalInset = Math.Max(3 * Zoom, (key.BorderWidthDp + 3) * Zoom) + shadowExtra;
        var availableWidth = Math.Max(1, rect.Width - horizontalInset * 2);
        var availableHeight = Math.Max(1, rect.Height - 2);
        if (key.AutoFitText && (formatted.Width > availableWidth || formatted.Height > availableHeight))
        {
            var scale = Math.Min(availableWidth / Math.Max(1, formatted.Width),
                availableHeight / Math.Max(1, formatted.Height));
            size = Math.Max(4, size * scale);
            formatted = new FormattedText(
                text,
                CultureInfo.CurrentCulture,
                FlowDirection.LeftToRight,
                typeface,
                size,
                new SolidColorBrush(color));
        }
        var desiredOriginX = rightAnchored
            ? rect.Center.X + offsetX - formatted.Width
            : rect.X + (rect.Width - formatted.Width) / 2 + offsetX;
        if (key.AutoFitText && formatted.Width <= availableWidth)
        {
            var minimumOriginX = rect.Left + horizontalInset;
            var maximumOriginX = rect.Right - horizontalInset - formatted.Width;
            desiredOriginX = maximumOriginX >= minimumOriginX
                ? Math.Clamp(desiredOriginX, minimumOriginX, maximumOriginX)
                : minimumOriginX;
        }
        var origin = new Point(
            desiredOriginX,
            rect.Y + (rect.Height - formatted.Height) / 2 + offsetY);
        if (key.TextShadowEnabled)
        {
            var shadowColor = ToColor(key.TextShadowColor);
            var shadowText = new FormattedText(
                text,
                CultureInfo.CurrentCulture,
                FlowDirection.LeftToRight,
                typeface,
                size,
                new SolidColorBrush(shadowColor));
            var shadowOrigin = origin + new Vector(
                key.TextShadowOffsetXDp * Zoom,
                key.TextShadowOffsetYDp * Zoom);
            if (_panning && _touchGesture)
            {
                context.DrawText(shadowText, shadowOrigin);
            }
            else
            {
                DrawTextShadow(context, text, typeface, size, shadowText, shadowOrigin,
                    key.TextShadowBlurRadiusDp * Zoom, shadowColor);
            }
        }
        context.DrawText(formatted, origin);
    }

    private static void DrawTextShadow(
        DrawingContext context,
        string value,
        Typeface typeface,
        double fontSize,
        FormattedText sharpText,
        Point origin,
        double blurRadius,
        Color color)
    {
        if (blurRadius <= 0.01)
        {
            context.DrawText(sharpText, origin);
            return;
        }

        var softened = Color.FromArgb((byte)Math.Max(1, color.A / 8), color.R, color.G, color.B);
        var brushText = new FormattedText(
            value,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            typeface,
            fontSize,
            new SolidColorBrush(softened));
        for (var i = 0; i < 8; i++)
        {
            var angle = i * Math.PI / 4;
            context.DrawText(brushText, origin + new Vector(Math.Cos(angle) * blurRadius, Math.Sin(angle) * blurRadius));
        }
    }

    private void DrawSelection(DrawingContext context, KeyDefinition key, bool drawResizeHandles)
    {
        var rect = WorldRectToScreen(key);
        var pen = new Pen(new SolidColorBrush(Color.Parse("#FF9800")), 2);
        var padding = (drawResizeHandles ? SelectionPadding : MultiSelectionPadding) * Zoom;
        context.DrawRectangle(null, pen, rect.Inflate(padding));
        if (!drawResizeHandles)
        {
            return;
        }

        var brush = new SolidColorBrush(Color.Parse("#FF9800"));
        foreach (var handle in ResizeHandles(key).Values)
        {
            context.FillRectangle(brush, handle);
        }
    }

    private void DrawRain(DrawingContext context, string keyId)
    {
        if (_host is null)
        {
            return;
        }

        foreach (var particle in _rain.Where(item => item.KeyId == keyId))
        {
            var rect = new Rect(
                particle.X * Zoom + Pan.X - particle.Width * Zoom / 2,
                (particle.BottomY - particle.Height) * Zoom + Pan.Y,
                particle.Width * Zoom,
                particle.Height * Zoom);
            var color = ToColor(particle.Color);
            if (particle.GlowRadius > 0)
            {
                for (var layer = 3; layer >= 1; layer--)
                {
                    var spread = particle.GlowRadius * Zoom * layer / 3;
                    var alpha = (byte)Math.Max(1, Math.Round(color.A * (0.12 / layer)));
                    var corner = _host.Document.GlobalSettings.GlobalRainCornerRadiusDp * Zoom + spread;
                    context.DrawRectangle(
                        new SolidColorBrush(Color.FromArgb(alpha, color.R, color.G, color.B)),
                        null,
                        rect.Inflate(spread),
                        corner,
                        corner);
                }
            }
            context.DrawRectangle(
                new SolidColorBrush(color),
                null,
                rect,
                _host.Document.GlobalSettings.GlobalRainCornerRadiusDp * Zoom,
                _host.Document.GlobalSettings.GlobalRainCornerRadiusDp * Zoom);
        }
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        var changed = false;
        if (_rain.Count > 0)
        {
            foreach (var particle in _rain)
            {
                if (particle.Released)
                {
                    particle.BottomY -= particle.Speed;
                    changed = true;
                }
                else if (particle.Height < particle.MaxHeight)
                {
                    var height = Math.Min(particle.MaxHeight, particle.Height + particle.Speed);
                    changed |= Math.Abs(height - particle.Height) > double.Epsilon;
                    particle.Height = height;
                }
            }

            changed |= _rain.RemoveAll(particle =>
                (particle.BottomY - particle.Height) * Zoom + Pan.Y < -100) > 0;
        }

        if (changed)
        {
            InvalidateVisual();
        }

        if (_rain.Count == 0 || _rain.All(particle => !particle.Released && particle.Height >= particle.MaxHeight))
        {
            _timer.Stop();
        }
    }

    private void OnHostChanged()
    {
        Dispatcher.UIThread.Post(() =>
        {
            CapturePressedTransitions();
            InvalidateVisual();
        }, DispatcherPriority.Render);
    }

    private void CapturePressedTransitions()
    {
        if (_host is null)
        {
            return;
        }

        var animationNeeded = false;
        foreach (var key in _host.Document.Keys)
        {
            var pressed = _host.State.IsPressed(key.BoundKey);
            _previousPressed.TryGetValue(key.Id, out var previous);
            if (pressed && !previous && key.RainEnabled)
            {
                _rain.Add(new RainParticle
                {
                    KeyId = key.Id,
                    X = key.CenterX + key.RainOffsetXDp,
                    BottomY = key.CenterY - key.Height / 2 - key.RainOffsetYDp,
                    Width = (key.Width + key.BorderWidthDp) * key.RainWidthPercent / 100,
                    Color = key.RainColor,
                    Speed = Math.Max(0.5, _host.Document.RainSpeedDp),
                    Height = Math.Min(
                        Math.Max(0, _host.Document.GlobalSettings.GlobalRainReserveHeightDp),
                        Math.Max(0, _host.Document.GlobalSettings.GlobalRainHeightDp)),
                    MaxHeight = Math.Max(0, _host.Document.GlobalSettings.GlobalRainHeightDp),
                    GlowRadius = key.RainGlowEnabled ? key.RainGlowRadiusDp : 0
                });
                animationNeeded = true;
            }
            else if (!pressed && previous)
            {
                foreach (var particle in _rain.Where(item => item.KeyId == key.Id && !item.Released))
                {
                    particle.Released = true;
                    animationNeeded = true;
                }
            }

            _previousPressed[key.Id] = pressed;
        }

        if (animationNeeded && !_timer.IsEnabled)
        {
            _timer.Start();
        }
    }

    private Bitmap? ResolveBitmap(string? assetName)
    {
        if (_host is null || string.IsNullOrWhiteSpace(assetName))
        {
            return null;
        }

        if (_images.TryGetValue(assetName, out var cached))
        {
            return cached;
        }

        try
        {
            var path = _host.ResolveAsset(assetName);
            cached = path is null ? null : new Bitmap(path);
        }
        catch
        {
            cached = null;
        }

        _images[assetName] = cached;
        return cached;
    }

    public void ClearAssetCache()
    {
        foreach (var image in _images.Values)
        {
            image?.Dispose();
        }
        _images.Clear();
        foreach (var collection in _fontCollections.Values)
        {
            FontManager.Current.RemoveFontCollection(collection.Key);
        }
        _fontCollections.Clear();
        _fonts.Clear();
        InvalidateVisual();
    }

    private KeyDefinition? HitTestKey(Point world)
    {
        return _host is null ? null : HitTest(_host.Document.Keys, world);
    }

    private static KeyDefinition? HitTest(IEnumerable<KeyDefinition> keys, Point world)
    {
        return keys
            .OrderByDescending(key => key.ZIndex)
            .FirstOrDefault(key => new Rect(
                key.CenterX - key.Width / 2,
                key.CenterY - key.Height / 2,
                key.Width,
                key.Height).Contains(world));
    }

    private Rect WorldRectToScreen(KeyDefinition key)
        => new(
            (key.CenterX - key.Width / 2) * Zoom + Pan.X,
            (key.CenterY - key.Height / 2) * Zoom + Pan.Y,
            key.Width * Zoom,
            key.Height * Zoom);

    private Point ScreenToWorld(Point point)
        => new((point.X - Pan.X) / Zoom, (point.Y - Pan.Y) / Zoom);

    private Rect VisibleWorldBounds()
    {
        var topLeft = ScreenToWorld(default);
        var bottomRight = ScreenToWorld(new Point(Bounds.Width, Bounds.Height));
        return new Rect(
            topLeft.X,
            topLeft.Y,
            Math.Max(0, bottomRight.X - topLeft.X),
            Math.Max(0, bottomRight.Y - topLeft.Y));
    }

    private Dictionary<ResizeEdge, Rect> ResizeHandles(KeyDefinition key)
    {
        var rect = WorldRectToScreen(key);
        var size = Math.Max(6, HandleSize * Zoom);
        return new Dictionary<ResizeEdge, Rect>
        {
            [ResizeEdge.Left] = new(rect.Left - size / 2, rect.Center.Y - size / 2, size, size),
            [ResizeEdge.Right] = new(rect.Right - size / 2, rect.Center.Y - size / 2, size, size),
            [ResizeEdge.Top] = new(rect.Center.X - size / 2, rect.Top - size / 2, size, size),
            [ResizeEdge.Bottom] = new(rect.Center.X - size / 2, rect.Bottom - size / 2, size, size)
        };
    }

    private ResizeEdge HitResizeHandle(Point screen, KeyDefinition key)
    {
        foreach (var (edge, rect) in ResizeHandles(key))
        {
            if (rect.Contains(screen))
            {
                return edge;
            }
        }

        return ResizeEdge.None;
    }

    private void ResizeSelected(Vector delta)
    {
        if (SelectedKey is null)
        {
            return;
        }

        var key = SelectedKey;
        var minimum = Math.Max(8, HandleSize + key.BorderWidthDp * 2);
        switch (_resizeEdge)
        {
            case ResizeEdge.Left:
            {
                var right = key.CenterX + key.Width / 2;
                var left = Math.Min(right - minimum, key.CenterX - key.Width / 2 + delta.X);
                key.Width = Math.Max(minimum, right - left);
                key.CenterX = right - key.Width / 2;
                break;
            }
            case ResizeEdge.Right:
            {
                var left = key.CenterX - key.Width / 2;
                var right = Math.Max(left + minimum, key.CenterX + key.Width / 2 + delta.X);
                key.Width = Math.Max(minimum, right - left);
                key.CenterX = left + key.Width / 2;
                break;
            }
            case ResizeEdge.Top:
            {
                var bottom = key.CenterY + key.Height / 2;
                var top = Math.Min(bottom - minimum, key.CenterY - key.Height / 2 + delta.Y);
                key.Height = Math.Max(minimum, bottom - top);
                key.CenterY = bottom - key.Height / 2;
                break;
            }
            case ResizeEdge.Bottom:
            {
                var top = key.CenterY - key.Height / 2;
                var bottom = Math.Max(top + minimum, key.CenterY + key.Height / 2 + delta.Y);
                key.Height = Math.Max(minimum, bottom - top);
                key.CenterY = top + key.Height / 2;
                break;
            }
        }
    }

    private void MoveBatchSelection(Vector delta)
    {
        if (_selectedKeys.Count == 0)
        {
            return;
        }

        var viewport = VisibleWorldBounds();
        var selectionBounds = GetKeyBounds(_selectedKeys);
        var dx = KeyViewerLayoutGrid.ClampTranslationToViewport(
            delta.X,
            selectionBounds.Left,
            selectionBounds.Right,
            viewport.Left,
            viewport.Right);
        var dy = KeyViewerLayoutGrid.ClampTranslationToViewport(
            delta.Y,
            selectionBounds.Top,
            selectionBounds.Bottom,
            viewport.Top,
            viewport.Bottom);

        foreach (var key in _selectedKeys)
        {
            key.CenterX += dx;
            key.CenterY += dy;
        }
    }

    private static void ConstrainKeysToViewport(IReadOnlyList<KeyDefinition> keys, Rect viewport)
    {
        if (keys.Count == 0 || viewport.Width <= 0 || viewport.Height <= 0)
        {
            return;
        }

        var bounds = GetKeyBounds(keys);
        var dx = KeyViewerLayoutGrid.ClampTranslationToViewport(0, bounds.Left, bounds.Right, viewport.Left, viewport.Right);
        var dy = KeyViewerLayoutGrid.ClampTranslationToViewport(0, bounds.Top, bounds.Bottom, viewport.Top, viewport.Bottom);
        if (dx == 0 && dy == 0)
        {
            return;
        }

        foreach (var key in keys)
        {
            key.CenterX += dx;
            key.CenterY += dy;
        }
    }

    private static Rect GetKeyBounds(IReadOnlyList<KeyDefinition> keys)
    {
        var left = keys.Min(key => key.CenterX - key.Width / 2);
        var top = keys.Min(key => key.CenterY - key.Height / 2);
        var right = keys.Max(key => key.CenterX + key.Width / 2);
        var bottom = keys.Max(key => key.CenterY + key.Height / 2);
        return new Rect(left, top, Math.Max(0, right - left), Math.Max(0, bottom - top));
    }

    private void BringToFront(KeyDefinition key)
    {
        if (_host is null || _host.Document.Keys.Count == 0)
        {
            return;
        }
        var maximum = _host.Document.Keys.Max(item => item.ZIndex);
        if (key.ZIndex != maximum)
        {
            key.ZIndex = maximum + 1;
            _host.NotifyEdited();
        }
    }

    private void SetSingleSelection(KeyDefinition? key)
    {
        _selectedKeys.Clear();
        if (key is not null)
        {
            _selectedKeys.Add(key);
        }
        _selected = key;
        SelectedKeyChanged?.Invoke(key);
        SelectionChanged?.Invoke();
        InvalidateVisual();
    }

    private void AddSelection(KeyDefinition key)
    {
        if (!_selectedKeys.Contains(key))
        {
            _selectedKeys.Add(key);
        }
        _selected = key;
        SelectedKeyChanged?.Invoke(key);
        SelectionChanged?.Invoke();
        InvalidateVisual();
    }

    private void RemoveSelection(KeyDefinition key)
    {
        _selectedKeys.Remove(key);
        _selected = _selectedKeys.LastOrDefault();
        SelectedKeyChanged?.Invoke(_selected);
        SelectionChanged?.Invoke();
        InvalidateVisual();
    }

    private void ReplaceSelection(IEnumerable<KeyDefinition> keys, KeyDefinition? primary)
    {
        _selectedKeys.Clear();
        _selectedKeys.AddRange(keys);
        _selected = primary;
        SelectedKeyChanged?.Invoke(_selected);
        SelectionChanged?.Invoke();
        InvalidateVisual();
    }

    private void ClearSelection() => SetSingleSelection(null);

    private void SetZoom(double zoom, Point focus)
    {
        zoom = Math.Clamp(zoom, 0.2, 5);
        var worldFocus = ScreenToWorld(focus);
        Zoom = zoom;
        Pan = new Vector(focus.X - worldFocus.X * Zoom, focus.Y - worldFocus.Y * Zoom);
        InvalidateVisual();
    }

    private void InvalidateAfterPointerMove()
    {
        if (!_touchGesture)
        {
            InvalidateVisual();
            return;
        }

        var now = Stopwatch.GetTimestamp();
        if (_lastTouchRenderTimestamp == 0
            || Stopwatch.GetElapsedTime(_lastTouchRenderTimestamp, now) >= TimeSpan.FromMilliseconds(33))
        {
            _lastTouchRenderTimestamp = now;
            InvalidateVisual();
        }
    }

    private static Rect CoverSource(double imageWidth, double imageHeight, double targetWidth, double targetHeight)
    {
        var sourceAspect = imageWidth / imageHeight;
        var targetAspect = targetWidth / targetHeight;
        if (sourceAspect > targetAspect)
        {
            var width = imageHeight * targetAspect;
            return new Rect((imageWidth - width) / 2, 0, width, imageHeight);
        }

        var height = imageWidth / targetAspect;
        return new Rect(0, (imageHeight - height) / 2, imageWidth, height);
    }

    private static Rect FitSource(double imageWidth, double imageHeight, double targetWidth, double targetHeight)
    {
        var imageAspect = imageWidth / imageHeight;
        var targetAspect = targetWidth / targetHeight;
        return imageAspect > targetAspect
            ? new Rect((imageWidth - imageHeight * targetAspect) / 2, 0, imageHeight * targetAspect, imageHeight)
            : new Rect(0, (imageHeight - imageWidth / targetAspect) / 2, imageWidth, imageWidth / targetAspect);
    }

    private static Color ToColor(int argb)
    {
        var value = unchecked((uint)argb);
        return Color.FromArgb(
            (byte)(value >> 24),
            (byte)(value >> 16),
            (byte)(value >> 8),
            (byte)value);
    }

    private FontFamily FontFor(KeyDefinition key)
    {
        var font = key.FontAsset?.Trim() ?? string.Empty;
        if (font.Length == 0 || font.Equals("builtin:maplestory", StringComparison.OrdinalIgnoreCase))
        {
            return DefaultKeyViewerFont;
        }

        if (font.Equals("builtin:adofai", StringComparison.OrdinalIgnoreCase)) return AdoFaiFont;
        if (font.Equals("builtin:sans", StringComparison.OrdinalIgnoreCase)) return FontFamily.Default;
        if (font.Equals("builtin:serif", StringComparison.OrdinalIgnoreCase)) return new FontFamily("serif");
        if (font.Equals("builtin:monospace", StringComparison.OrdinalIgnoreCase)) return new FontFamily("monospace");
        if (font.StartsWith("system:", StringComparison.OrdinalIgnoreCase))
        {
            return OperatingSystem.IsWindows() && font.Length > "system:".Length
                ? new FontFamily(font["system:".Length..])
                : DefaultKeyViewerFont;
        }

        if (_fonts.TryGetValue(font, out var cached))
        {
            return cached ?? DefaultKeyViewerFont;
        }

        FontFamily? family = null;
        EmbeddedFontCollection? collection = null;
        try
        {
            var path = _host?.ResolveAsset(font);
            if (path is not null)
            {
                var collectionKey = new Uri($"fonts:keyviewer-user-{Guid.NewGuid():N}", UriKind.Absolute);
                collection = new EmbeddedFontCollection(collectionKey, new Uri(path));
                FontManager.Current.AddFontCollection(collection);
                var loadedFamily = collection.FirstOrDefault();
                if (loadedFamily is not null)
                {
                    family = new FontFamily($"{collectionKey}#{loadedFamily.Name}");
                    _fontCollections[font] = collection;
                    collection = null;
                }
            }
        }
        catch
        {
            family = null;
        }
        finally
        {
            if (collection is not null)
            {
                FontManager.Current.RemoveFontCollection(collection.Key);
            }
        }

        _fonts[font] = family;
        return family ?? DefaultKeyViewerFont;
    }

    private static double Snap(double value) => KeyViewerLayoutGrid.Snap(value);
    private static double DigitScale(int length) => length switch
    {
        >= 9 => 0.5,
        8 => 0.55,
        7 => 0.6,
        6 => 0.7,
        5 => 0.8,
        4 => 0.9,
        _ => 1
    };

    public void Dispose()
    {
        _timer.Stop();
        if (_host is not null)
        {
            _host.Changed -= OnHostChanged;
        }
        foreach (var image in _images.Values)
        {
            image?.Dispose();
        }
        _images.Clear();
        foreach (var collection in _fontCollections.Values)
        {
            FontManager.Current.RemoveFontCollection(collection.Key);
        }
        _fontCollections.Clear();
        _fonts.Clear();
    }

    private enum ResizeEdge
    {
        None,
        Left,
        Right,
        Top,
        Bottom
    }

    private sealed class RainParticle
    {
        public string KeyId { get; init; } = string.Empty;
        public double X { get; init; }
        public double BottomY { get; set; }
        public double Width { get; init; }
        public double Height { get; set; }
        public double MaxHeight { get; init; }
        public double Speed { get; init; }
        public int Color { get; init; }
        public double GlowRadius { get; init; }
        public bool Released { get; set; }
    }
}
