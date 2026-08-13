using Android.App;
using Android.Graphics;
using Android.OS;
using Android.Content.PM;
using Android.Views;
using Avalonia.Android;
using System.Runtime.Versioning;

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
        ApplyImmersiveLayout();
    }

    protected override void OnResume()
    {
        base.OnResume();
        ApplyImmersiveLayout();
        (Avalonia.Application.Current as KeyViewerAndroidApp)?.SetEditorForeground(true);
    }

    public override void OnWindowFocusChanged(bool hasFocus)
    {
        base.OnWindowFocusChanged(hasFocus);
        if (hasFocus)
        {
            ApplyImmersiveLayout();
        }
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

    private void ApplyImmersiveLayout()
    {
        var window = Window;
        if (window is null)
        {
            return;
        }

#pragma warning disable CA1422
        window.SetStatusBarColor(Color.Transparent);
        window.SetNavigationBarColor(Color.Transparent);
#pragma warning restore CA1422

#pragma warning disable CA1416
        if (Build.VERSION.SdkInt >= BuildVersionCodes.R)
        {
            ApplyModernImmersiveLayout(window);
            return;
        }

        if (Build.VERSION.SdkInt >= BuildVersionCodes.P)
        {
            ApplyLegacyCutoutLayout(window);
        }
#pragma warning restore CA1416

#pragma warning disable CA1422
        window.DecorView.SystemUiFlags = SystemUiFlags.LayoutStable
            | SystemUiFlags.LayoutFullscreen
            | SystemUiFlags.LayoutHideNavigation
            | SystemUiFlags.Fullscreen
            | SystemUiFlags.HideNavigation
            | SystemUiFlags.ImmersiveSticky;
#pragma warning restore CA1422
    }

    [SupportedOSPlatform("android30.0")]
    private static void ApplyModernImmersiveLayout(Window window)
    {
        if (window.Attributes is { } attributes)
        {
            attributes.LayoutInDisplayCutoutMode = LayoutInDisplayCutoutMode.Always;
            window.Attributes = attributes;
        }

#pragma warning disable CA1422
        window.SetDecorFitsSystemWindows(false);
#pragma warning restore CA1422
        var controller = window.InsetsController;
        if (controller is null)
        {
            return;
        }

        controller.Hide(WindowInsets.Type.SystemBars());
        controller.SystemBarsBehavior =
            (int)WindowInsetsControllerBehavior.ShowTransientBarsBySwipe;
    }

    [SupportedOSPlatform("android28.0")]
    private static void ApplyLegacyCutoutLayout(Window window)
    {
        if (window.Attributes is not { } attributes)
        {
            return;
        }

        attributes.LayoutInDisplayCutoutMode = LayoutInDisplayCutoutMode.ShortEdges;
        window.Attributes = attributes;
    }

}
