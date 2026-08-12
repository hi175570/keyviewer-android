using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using KeyViewer.UI;

namespace KeyViewer.Desktop;

public sealed class App : Application
{
    private KeyViewerAppHost? _host;
    private DesktopPlatformServices? _platform;

    public override void Initialize()
    {
        Styles.Add(new FluentTheme());
        Styles.Add(new StyleInclude(new Uri("avares://KeyViewer.Desktop"))
        {
            Source = new Uri("avares://Avalonia.Controls.ColorPicker/Themes/Fluent/Fluent.xaml")
        });
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            // The overlay is a real second window.  Without this mode, closing
            // the editor leaves that tool window (and the global hook process)
            // alive with no taskbar entry.
            desktop.ShutdownMode = ShutdownMode.OnMainWindowClose;
            _host = new KeyViewerAppHost();
            _platform = new DesktopPlatformServices(_host);
            RequestedThemeVariant = _host.Document.GlobalSettings.DarkModeEnabled
                ? ThemeVariant.Dark
                : ThemeVariant.Light;
            desktop.MainWindow = new MainWindow(_host, _platform);
            desktop.Exit += (_, _) =>
            {
                _platform.Dispose();
                _host.Dispose();
            };
        }

        base.OnFrameworkInitializationCompleted();
    }
}
