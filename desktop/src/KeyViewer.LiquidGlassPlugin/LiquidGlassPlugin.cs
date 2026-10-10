using KeyViewer.Abstractions;

namespace KeyViewer.LiquidGlassPlugin;

/// <summary>
/// Defines the material used by the host's native backdrop compositor. The
/// plugin does not capture or shade KeyViewer's own canvas; the Windows host
/// interprets RefractionStrength against its external HostBackdrop surface.
/// </summary>
public sealed class LiquidGlassPlugin : IKeyViewerPlugin, IKeySurfaceEffect
{
    public const string PluginId = "io.keyviewer.liquid-glass";
    private IDisposable? _registration;

    public KeyViewerPluginManifest Manifest { get; } = new(
        PluginId,
        "液态玻璃按键渲染",
        "1.1.0")
    {
        Author = "KeyViewer",
        Description = "使用 Windows 原生后方画面采样与凸透镜位移渲染全部按键。"
    };

    public string Id => PluginId;
    public int Priority => 1000;

    public void Initialize(IPluginContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        _registration = context.RegisterKeySurfaceEffect(this);
        context.LogInfo("液态玻璃材质已挂载；Windows 悬浮窗将使用真实 HostBackdrop 折射。");
    }

    public bool TryCreateStyle(KeySurfaceContext context, out KeySurfaceStyle style)
    {
        ArgumentNullException.ThrowIfNull(context);
        var ambient = context.DarkMode ? unchecked((int)0xFF1F2B3A) : unchecked((int)0xFFF4FBFF);
        var depth = context.DarkMode ? unchecked((int)0xFF07101D) : unchecked((int)0xFF49627D);
        var source = context.FillColor;
        var border = context.BorderColor;

        style = new KeySurfaceStyle
        {
            BaseColor = WithAlpha(Blend(source, ambient, context.IsPressed ? 0.48 : 0.64),
                context.IsPressed ? 0xB8 : 0x96),
            MidColor = WithAlpha(Blend(source, ambient, context.IsPressed ? 0.28 : 0.45),
                context.IsPressed ? 0xA8 : 0x72),
            DepthColor = WithAlpha(Blend(source, depth, context.IsPressed ? 0.58 : 0.42),
                context.IsPressed ? 0xB8 : 0x88),
            SpecularColor = context.DarkMode
                ? unchecked((int)0xD8EAF6FF)
                : unchecked((int)0xEFFFFFFF),
            RimLightColor = context.DarkMode
                ? unchecked((int)0xC8D9F4FF)
                : unchecked((int)0xE8FFFFFF),
            RimShadeColor = WithAlpha(Blend(border, depth, 0.34), 0xA8),
            ShadowColor = context.DarkMode
                ? unchecked((int)0x98000000)
                : unchecked((int)0x68081726),
            GlowColor = WithAlpha(Blend(source, unchecked((int)0xFFFFFFFF), 0.24),
                context.IsPressed ? 0xB0 : 0x54),
            SurfaceOpacity = context.IsPressed ? 0.86 : 0.74,
            RefractionStrength = context.IsPressed ? 8.8 : 6.6,
            BackdropBlurRadius = context.IsPressed ? 1.9 : 1.35,
            ChromaticAberration = context.IsPressed ? 2.1 : 1.45,
            SpecularStrength = context.IsPressed ? 1.22 : 1,
            ShadowDepth = context.IsPressed ? 2.2 : 5.2,
            PressInset = 1.6,
            CornerRadiusBoost = Math.Min(8, Math.Min(context.Width, context.Height) * 0.12)
        };
        return true;
    }

    public void Dispose()
    {
        _registration?.Dispose();
        _registration = null;
    }

    private static int Blend(int from, int to, double amount)
    {
        amount = Math.Clamp(amount, 0, 1);
        var source = unchecked((uint)from);
        var target = unchecked((uint)to);
        static byte Channel(uint value, int shift) => (byte)((value >> shift) & 0xFF);
        static byte Mix(byte a, byte b, double t) => (byte)Math.Round(a + (b - a) * t);
        var alpha = Mix(Channel(source, 24), Channel(target, 24), amount);
        var red = Mix(Channel(source, 16), Channel(target, 16), amount);
        var green = Mix(Channel(source, 8), Channel(target, 8), amount);
        var blue = Mix(Channel(source, 0), Channel(target, 0), amount);
        return unchecked((int)((uint)alpha << 24 | (uint)red << 16 | (uint)green << 8 | blue));
    }

    private static int WithAlpha(int color, int alpha)
        => unchecked((int)((unchecked((uint)color) & 0x00FFFFFF)
            | ((uint)(byte)Math.Clamp(alpha, 0, 255) << 24)));
}
