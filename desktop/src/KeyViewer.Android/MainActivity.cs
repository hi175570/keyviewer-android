using Android.App;
using Android.OS;
using Android.Content.PM;
using Avalonia.Android;

namespace KeyViewer.Android;

[Activity(
    Label = "KeyViewer",
    Theme = "@style/KeyViewerTheme",
    Icon = "@drawable/icon",
    MainLauncher = true,
    ScreenOrientation = ScreenOrientation.Landscape,
    ConfigurationChanges = ConfigChanges.Orientation
        | ConfigChanges.ScreenSize
        | ConfigChanges.KeyboardHidden
        | ConfigChanges.ScreenLayout
        | ConfigChanges.UiMode)]
public sealed class MainActivity : AvaloniaMainActivity
{
    public static MainActivity? Current { get; private set; }

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        Current = this;
        base.OnCreate(savedInstanceState);
    }

    protected override void OnResume()
    {
        base.OnResume();
        (Avalonia.Application.Current as KeyViewerAndroidApp)?.SetEditorForeground(true);
    }

    protected override void OnPause()
    {
        (Avalonia.Application.Current as KeyViewerAndroidApp)?.SetEditorForeground(false);
        base.OnPause();
    }

    protected override void OnDestroy()
    {
        if (ReferenceEquals(Current, this))
        {
            Current = null;
        }
        base.OnDestroy();
    }

}
