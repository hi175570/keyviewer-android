using Avalonia;
using KeyViewer.Abstractions;

namespace KeyViewer.UI.Rendering;

/// <summary>
/// Platform-neutral key geometry consumed by a native backdrop compositor.
/// The floating Windows overlay uses it to refract pixels that are physically
/// behind the window instead of sampling KeyViewer's own canvas.
/// </summary>
public sealed record LiquidGlassVisualGeometry(
    string KeyId,
    Rect Surface,
    double CornerRadius,
    KeySurfaceStyle Style,
    bool Pressed);
