using KeyViewer.UI.Rendering;

namespace KeyViewer.Desktop;

/// <summary>
/// Cross-platform fallback. Linux keeps the shared desktop application and
/// renders ordinary keys; only Windows 11 exposes an external host backdrop.
/// </summary>
internal sealed class WindowsLiquidGlassCompositionHost : IDisposable
{
    public static bool TryCreate(nint window, out WindowsLiquidGlassCompositionHost? host, out string error)
    {
        _ = window;
        host = null;
        error = "真实背景折射目前只支持 Windows 11。";
        return false;
    }

    public void Update(
        float clientWidth,
        float clientHeight,
        double renderScaling,
        IReadOnlyList<LiquidGlassVisualGeometry> keys)
    {
        _ = clientWidth;
        _ = clientHeight;
        _ = renderScaling;
        _ = keys;
    }

    public void Dispose()
    {
    }
}
