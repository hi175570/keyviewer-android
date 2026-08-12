using KeyViewer.UI;

namespace KeyViewer.Desktop;

public sealed class DesktopPlatformServices : IKeyViewerPlatformServices
{
    private readonly KeyViewerAppHost _host;
    private OverlayWindow? _overlay;

    public DesktopPlatformServices(KeyViewerAppHost host) => _host = host;

    public bool IsOverlayVisible => _overlay?.IsVisible == true;

    public string PlatformName => OperatingSystem.IsWindows() ? "Windows" : "Linux";

    public event Action<bool>? OverlayVisibilityChanged;

    public void ToggleOverlay()
    {
        if (_overlay is { IsVisible: true })
        {
            _overlay.Close();
            return;
        }

        _overlay = new OverlayWindow(_host);
        _overlay.Closed += OnOverlayClosed;
        _overlay.Show();
        OverlayVisibilityChanged?.Invoke(true);
    }

    public void RefreshOverlay() => _overlay?.RefreshLayout();

    public void ResetOverlayPosition() => _overlay?.ResetPosition();

    private void OnOverlayClosed(object? sender, EventArgs e)
    {
        if (_overlay is not null)
        {
            _overlay.Closed -= OnOverlayClosed;
            _overlay = null;
        }

        OverlayVisibilityChanged?.Invoke(false);
    }

    public void Dispose()
    {
        if (_overlay is not null)
        {
            _overlay.Closed -= OnOverlayClosed;
            _overlay.Close();
            _overlay = null;
        }
    }
}
