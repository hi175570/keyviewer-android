using KeyViewer.Abstractions;
using KeyViewer.Core.Input;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Tests;

public sealed class InputRouterTests
{
    [Fact]
    public void Restriction_runs_before_key_features_and_protects_escape()
    {
        var document = CreateDocument("a");
        document.Keys[0].GhostKey = true;
        document.Keys[0].MappingEnabled = true;
        document.Keys[0].MappedKey = "b";
        document.GlobalSettings.KeyRestrictionEnabled = true;
        document.GlobalSettings.KeyRestrictionBlockedKeys = "a, esc";
        var router = new InputRouter(document);

        var blocked = router.Route(Down("a"));
        var missing = router.Route(Down("q"));
        var escape = router.Route(Down("esc"));

        Assert.False(blocked.ForwardToSystem);
        Assert.False(blocked.ShowInViewer);
        Assert.Equal("restricted-explicit", blocked.Reason);
        Assert.False(missing.ForwardToSystem);
        Assert.Equal("restricted-not-on-canvas", missing.Reason);
        Assert.True(escape.ForwardToSystem);
        Assert.Equal("protected-system-key", escape.Reason);
    }

    [Fact]
    public void Ghost_and_mapping_keep_source_visual_feedback()
    {
        var document = CreateDocument("a", "b");
        document.Keys[0].GhostKey = true;
        document.Keys[1].MappingEnabled = true;
        document.Keys[1].MappedKey = "c";
        var router = new InputRouter(document);

        var ghost = router.Route(Down("a"));
        var mapped = router.Route(Down("b"));

        Assert.True(ghost.ShowInViewer);
        Assert.True(ghost.CountPress);
        Assert.False(ghost.ForwardToSystem);
        Assert.Equal("ghost-key", ghost.Reason);
        Assert.True(mapped.ShowInViewer);
        Assert.False(mapped.ForwardToSystem);
        Assert.Equal("c", mapped.OutputKey);
        Assert.Equal("mapped", mapped.Reason);
    }

    [Fact]
    public void Repeat_is_not_counted_and_state_is_released()
    {
        var document = CreateDocument("a");
        var router = new InputRouter(document);
        var state = new KeyStateService(document);

        state.Apply(router.Route(Down("a")));
        state.Apply(router.Route(new KeyInputEvent("a", KeyEventState.Repeat, "test", DateTimeOffset.UtcNow)));
        Assert.True(state.IsPressed("a"));
        Assert.Equal(1, state.GetCount("a"));
        state.Apply(router.Route(new KeyInputEvent("a", KeyEventState.Up, "test", DateTimeOffset.UtcNow)));

        Assert.False(state.IsPressed("a"));
        Assert.Equal(1, document.TotalPressCount);
    }

    [Fact]
    public void Key_count_edit_recalculates_total_from_all_saved_counts()
    {
        var document = CreateDocument("a");
        document.KeyCounts["b"] = 8;
        document.TotalPressCount = 100;
        var state = new KeyStateService(document);

        state.SetCount("a", 42);

        Assert.Equal(42, state.GetCount("a"));
        Assert.Equal(50, document.TotalPressCount);
        state.SetCount("a", -1);
        Assert.Equal(0, state.GetCount("a"));
        Assert.Equal(8, document.TotalPressCount);
    }

    [Fact]
    public void Windows_back_alias_matches_Android_backspace_binding()
    {
        var document = CreateDocument("backspace");
        var route = new InputRouter(document).Route(Down("back"));

        Assert.True(route.ShowInViewer);
        Assert.True(route.CountPress);
        Assert.True(route.ForwardToSystem);
    }

    [Theory]
    [InlineData("back", "backspace")]
    [InlineData("delete", "del")]
    [InlineData("period", "dot")]
    [InlineData("apostrophe", "quote")]
    [InlineData("grave", "backquote")]
    [InlineData("lbracket", "bracketleft")]
    [InlineData("rbracket", "bracketright")]
    [InlineData("lwin", "lmeta")]
    [InlineData("rwin", "rmeta")]
    public void Desktop_aliases_use_Android_key_names(string desktopName, string androidName)
    {
        Assert.Equal(androidName, KeyNames.Normalize(desktopName));
        Assert.True(KeyNames.Equals(desktopName, androidName));
    }

    [Theory]
    [InlineData(0x08u, "backspace")]
    [InlineData(0x2Eu, "del")]
    [InlineData(0x5Bu, "lmeta")]
    [InlineData(0x5Cu, "rmeta")]
    [InlineData(0xBEu, "dot")]
    [InlineData(0xC0u, "backquote")]
    [InlineData(0xDBu, "bracketleft")]
    [InlineData(0xDDu, "bracketright")]
    [InlineData(0xDEu, "quote")]
    public void Windows_virtual_keys_emit_Android_names(uint virtualKey, string expected)
    {
        Assert.Equal(expected, VirtualKeyNames.ToName(virtualKey));
        Assert.True(KeyNames.Equals(expected,
            VirtualKeyNames.ToName(VirtualKeyNames.ToVirtualKey(expected))));
    }

    [Theory]
    [InlineData(1, "esc")]
    [InlineData(14, "back")]
    [InlineData(30, "a")]
    [InlineData(97, "rctrl")]
    [InlineData(125, "lwin")]
    public void Linux_evdev_keys_round_trip(ushort evdevCode, string expected)
    {
        Assert.Equal(expected, LinuxKeyNames.ToName(evdevCode));
        Assert.Equal(evdevCode, LinuxKeyNames.ToCode(expected));
    }

    [Theory]
    [InlineData("backspace", 14)]
    [InlineData("del", 111)]
    [InlineData("lmeta", 125)]
    [InlineData("rmeta", 126)]
    [InlineData("linux_240", 240)]
    public void Linux_evdev_accepts_cross_platform_aliases(string key, ushort expected)
        => Assert.Equal(expected, LinuxKeyNames.ToCode(key));

    [Theory]
    [InlineData("esc")]
    [InlineData("escape")]
    [InlineData("lwin")]
    [InlineData("rwin")]
    [InlineData("lmeta")]
    [InlineData("rmeta")]
    public void System_keys_are_never_restricted(string key)
    {
        Assert.True(KeyNames.IsProtected(key));
    }

    private static KeyViewerDocument CreateDocument(params string[] keys)
    {
        return new KeyViewerDocument
        {
            Keys = keys.Select((key, index) => new KeyDefinition
            {
                BoundKey = key,
                CenterX = 100 + index * 60,
                CenterY = 100
            }).ToList()
        };
    }

    private static KeyInputEvent Down(string key)
        => new(key, KeyEventState.Down, "test", DateTimeOffset.UtcNow);
}
