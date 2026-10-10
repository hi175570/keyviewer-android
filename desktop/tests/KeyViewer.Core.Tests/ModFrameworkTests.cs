using KeyViewer.Abstractions;
using KeyViewer.Core.Input;
using KeyViewer.Core.Models;
using KeyViewer.ModFramework;
using LiquidGlass = KeyViewer.LiquidGlassPlugin.LiquidGlassPlugin;

namespace KeyViewer.Core.Tests;

public sealed class ModFrameworkTests
{
    [Fact]
    public void Surface_registry_uses_highest_priority_effect_and_unregisters_cleanly()
    {
        var registry = new KeySurfaceEffectRegistry();
        using var low = registry.Register(new StubEffect("low", 1, 0x11000000));
        using var high = registry.Register(new StubEffect("high", 100, 0x22000000));

        Assert.Equal(new[] { "high", "low" }, registry.EffectIds);
        Assert.True(registry.TryCreateStyle(CreateContext(), out var style));
        Assert.Equal(unchecked((int)0x22000000), style.BaseColor);

        high.Dispose();
        Assert.Equal(new[] { "low" }, registry.EffectIds);
        Assert.True(registry.TryCreateStyle(CreateContext(), out style));
        Assert.Equal(unchecked((int)0x11000000), style.BaseColor);
    }

    [Fact]
    public void Liquid_glass_plugin_registers_changes_pressed_style_and_unloads()
    {
        var registry = new KeySurfaceEffectRegistry();
        var services = new ServiceRegistry();
        services.Add(typeof(IKeySurfaceEffectRegistry), registry);
        var dataRoot = Path.Combine(Path.GetTempPath(), $"keyviewer-plugin-test-{Guid.NewGuid():N}");
        Directory.CreateDirectory(dataRoot);
        try
        {
            using var host = new PluginHost(
                new InputRouter(new KeyViewerDocument()),
                services,
                dataRoot);
            var status = host.LoadAssembly(typeof(LiquidGlass).Assembly.Location);

            Assert.True(status.IsLoaded, status.Error);
            Assert.Contains(LiquidGlass.PluginId, registry.EffectIds);
            Assert.True(registry.TryCreateStyle(CreateContext(false), out var released));
            Assert.True(registry.TryCreateStyle(CreateContext(true), out var pressed));
            Assert.NotEqual(released.BaseColor, pressed.BaseColor);
            Assert.NotEqual(released.ShadowDepth, pressed.ShadowDepth);

            Assert.True(host.Unload(LiquidGlass.PluginId));
            Assert.Empty(registry.EffectIds);
        }
        finally
        {
            Directory.Delete(dataRoot, true);
        }
    }

    [Fact]
    public void Liquid_glass_style_requests_visible_external_refraction()
    {
        using var plugin = new LiquidGlass();

        Assert.True(plugin.TryCreateStyle(CreateContext(), out var style));
        Assert.True(style.RefractionStrength >= 6);
        Assert.InRange(style.SurfaceOpacity, 0.5, 0.9);
        Assert.True(style.CornerRadiusBoost > 0);
    }

    private static KeySurfaceContext CreateContext(bool pressed = false)
        => new("space", pressed, true, 64, 48, 8,
            unchecked((int)0xFF7040E0), unchecked((int)0xFFC9B8FF));

    private sealed class StubEffect(string id, int priority, int color) : IKeySurfaceEffect
    {
        public string Id { get; } = id;
        public int Priority { get; } = priority;

        public bool TryCreateStyle(KeySurfaceContext context, out KeySurfaceStyle style)
        {
            style = new KeySurfaceStyle { BaseColor = color };
            return true;
        }
    }
}
