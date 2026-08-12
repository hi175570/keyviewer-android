using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using KeyViewer.UI;
using System.Diagnostics;

namespace KeyViewer.Android;

public sealed class KeyViewerAndroidApp : Avalonia.Application
{
    private KeyViewerAppHost? _host;
    private AndroidPlatformServices? _platform;

    public override void Initialize()
    {
        Styles.Add(new FluentTheme());
        Styles.Add(new StyleInclude(new Uri("avares://KeyViewer.Android"))
        {
            Source = new Uri("avares://Avalonia.Controls.ColorPicker/Themes/Fluent/Fluent.xaml")
        });
    }

    public override void OnFrameworkInitializationCompleted()
    {
        var startup = Stopwatch.StartNew();
        global::Android.Util.Log.Info("KV_Startup", "framework initialization started");
        var files = global::Android.App.Application.Context.FilesDir?.AbsolutePath;
        if (!string.IsNullOrWhiteSpace(files))
        {
            Environment.SetEnvironmentVariable("KEYVIEWER_DATA_ROOT", files);
        }

        _host = new KeyViewerAppHost();
        global::Android.Util.Log.Info("KV_Startup", $"host ready at {startup.ElapsedMilliseconds} ms");
        _platform = new AndroidPlatformServices(_host);
        global::Android.Util.Log.Info("KV_Startup", $"platform ready at {startup.ElapsedMilliseconds} ms");
        RequestedThemeVariant = _host.Document.GlobalSettings.DarkModeEnabled
            ? ThemeVariant.Dark
            : ThemeVariant.Light;

        if (ApplicationLifetime is IActivityApplicationLifetime activity)
        {
            activity.MainViewFactory = () =>
            {
                var view = new KeyViewerMainView(_host, _platform);
                global::Android.Util.Log.Info("KV_Startup", $"main view ready at {startup.ElapsedMilliseconds} ms");
                return view;
            };
        }
        else if (ApplicationLifetime is ISingleViewApplicationLifetime single)
        {
            single.MainView = new KeyViewerMainView(_host, _platform);
        }

        base.OnFrameworkInitializationCompleted();
        global::Android.Util.Log.Info("KV_Startup", $"framework initialization completed at {startup.ElapsedMilliseconds} ms");
    }

    internal void SetEditorForeground(bool foreground)
        => _platform?.SetEditorForeground(foreground);
}
