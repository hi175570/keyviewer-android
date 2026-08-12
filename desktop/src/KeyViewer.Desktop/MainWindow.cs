using System.Reflection;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Platform;
using KeyViewer.UI;

namespace KeyViewer.Desktop;

public sealed partial class MainWindow : Window
{
    public MainWindow(KeyViewerAppHost host, IKeyViewerPlatformServices platform)
    {
        Title = "KeyViewer";
        Width = 1000;
        Height = 600;
        MinWidth = 800;
        MinHeight = 460;
        ShowActivated = false;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = Avalonia.Media.Brushes.Transparent;
        Content = new KeyViewerMainView(host, platform);
        TrySetIcon();
    }

    private void TrySetIcon()
    {
        using var stream = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream("KeyViewer.Desktop.Assets.icon.png");
        if (stream is not null)
        {
            Icon = new WindowIcon(stream);
        }
    }
}
