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

public sealed record InputBackendOption(
    string Id,
    string DisplayName,
    string Description,
    bool IsEnabled = true);

public interface IInputBackendPlatformServices
{
    IReadOnlyList<InputBackendOption> InputBackendOptions { get; }
    string ActiveInputBackend { get; }
    string InputBackendStatus { get; }
    bool ActiveInputBackendSupportsRouting { get; }
    event Action? InputBackendChanged;
    void SetInputBackend(string id);
    void OpenInputBackendSettings();
    void RefreshInputBackendStatus();
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
