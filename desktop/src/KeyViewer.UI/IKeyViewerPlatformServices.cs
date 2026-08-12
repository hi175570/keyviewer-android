namespace KeyViewer.UI;

public interface IKeyViewerPlatformServices : IDisposable
{
    bool IsOverlayVisible { get; }
    string PlatformName { get; }
    event Action<bool>? OverlayVisibilityChanged;
    void ToggleOverlay();
    void RefreshOverlay();
    void ResetOverlayPosition();
}

public sealed class NullKeyViewerPlatformServices : IKeyViewerPlatformServices
{
    public bool IsOverlayVisible => false;
    public string PlatformName => "通用";
    public event Action<bool>? OverlayVisibilityChanged;

    public void ToggleOverlay() => OverlayVisibilityChanged?.Invoke(false);
    public void RefreshOverlay() { }
    public void ResetOverlayPosition() { }
    public void Dispose() { }
}
