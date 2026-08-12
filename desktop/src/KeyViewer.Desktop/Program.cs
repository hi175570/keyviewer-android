using Avalonia;
using Avalonia.Fonts.Inter;
using Avalonia.Media.Fonts;

namespace KeyViewer.Desktop;

internal static class Program
{
    [STAThread]
    public static void Main(string[] args)
        => BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .ConfigureFonts(manager => manager.AddFontCollection(new EmbeddedFontCollection(
                new Uri("fonts:KeyViewer", UriKind.Absolute),
                new Uri("avares://KeyViewer.UI/Assets/Fonts", UriKind.Absolute))))
            .LogToTrace();
}
