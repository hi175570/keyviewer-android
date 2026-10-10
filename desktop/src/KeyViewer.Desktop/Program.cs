using Avalonia;
using Avalonia.Fonts.Inter;
using Avalonia.Media.Fonts;
using System.Runtime.CompilerServices;

namespace KeyViewer.Desktop;

internal static class Program
{
    [STAThread]
    public static void Main(string[] args)
    {
        ModFrameworkBootstrap.Initialize();
        StartApplication(args);
    }

    // Keep Main's JIT body free of Avalonia type references. This guarantees
    // the Managed resolver is installed before the first UI dependency binds.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void StartApplication(string[] args)
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
