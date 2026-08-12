using Android.Runtime;
using Android.Content.Res;
using Avalonia;
using Avalonia.Android;
using Avalonia.Fonts.Inter;
using Avalonia.Media.Fonts;

namespace KeyViewer.Android;

[global::Android.App.Application]
public class Application : AvaloniaAndroidApplication<KeyViewerAndroidApp>
{
    public static event Action? DisplayConfigurationChanged;

    protected Application(nint javaReference, JniHandleOwnership transfer)
        : base(javaReference, transfer)
    {
    }

    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
        => base.CustomizeAppBuilder(builder)
            .WithInterFont()
            .ConfigureFonts(manager => manager.AddFontCollection(new EmbeddedFontCollection(
                new Uri("fonts:KeyViewer", UriKind.Absolute),
                new Uri("avares://KeyViewer.UI/Assets/Fonts", UriKind.Absolute))));

    public override void OnConfigurationChanged(Configuration newConfig)
    {
        base.OnConfigurationChanged(newConfig);
        DisplayConfigurationChanged?.Invoke();
    }
}
