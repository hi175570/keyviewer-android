using System.Reflection;
using System.Runtime.InteropServices;
using KeyViewer.Abstractions;
using KeyViewer.Core.Input;

namespace KeyViewer.Core.Tests;

public sealed class WindowsGlobalKeyboardSourceTests
{
    [Fact]
    public void Windows_keyboard_source_is_passive_by_default_and_supports_explicit_mapping()
    {
        using var source = new WindowsGlobalKeyboardSource();

        Assert.True(source.IsPassiveWhenUnmodified);
        Assert.True(source.SupportsMapping);
        var methodNames = typeof(WindowsGlobalKeyboardSource)
            .GetMethods(BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)
            .Select(method => method.Name)
            .ToHashSet(StringComparer.Ordinal);
        Assert.Contains("SendInput", methodNames);
        Assert.Contains("SendKey", methodNames);
        Assert.Contains("ForwardMapped", methodNames);
    }

    [Fact]
    public void Native_input_layout_matches_the_Windows_SendInput_contract()
    {
        var nativeInput = typeof(WindowsGlobalKeyboardSource)
            .GetNestedType("NativeInput", BindingFlags.NonPublic);

        Assert.NotNull(nativeInput);
        Assert.Equal(IntPtr.Size == 8 ? 40 : 28, Marshal.SizeOf(nativeInput));
    }

    [Fact]
    public void Ordinary_input_passes_through_but_explicit_mapping_replaces_it()
    {
        var ordinary = Context("r", "r", true, "passthrough");
        var mapped = Context("r", "e", false, "mapped");

        Assert.Equal(WindowsKeyboardRouteAction.PassThrough,
            WindowsKeyboardRoutePolicy.Decide(ordinary, "r").Action);
        var mapping = WindowsKeyboardRoutePolicy.Decide(mapped, "r");
        Assert.Equal(WindowsKeyboardRouteAction.Map, mapping.Action);
        Assert.Equal((uint)'E', mapping.TargetVirtualKey);
    }

    [Theory]
    [InlineData("r")]
    [InlineData("not-a-real-key")]
    public void Ineffective_mapping_never_turns_the_source_key_into_a_dead_key(string target)
    {
        var route = Context("r", target, false, "mapped");

        var decision = WindowsKeyboardRoutePolicy.Decide(route, "r");

        Assert.Equal(WindowsKeyboardRouteAction.PassThrough, decision.Action);
    }

    [Fact]
    public void Explicit_restriction_is_still_allowed_to_suppress()
    {
        var restricted = Context("x", "x", false, "restricted-not-on-canvas");

        Assert.Equal(WindowsKeyboardRouteAction.Suppress,
            WindowsKeyboardRoutePolicy.Decide(restricted, "x").Action);
    }

    [Fact]
    public void External_injected_input_is_routed_but_our_mapped_output_is_bypassed()
    {
        const nuint marker = 0x4B56524D;

        Assert.False(WindowsKeyboardRoutePolicy.ShouldBypassInjectedEvent(0, marker, marker));
        Assert.False(WindowsKeyboardRoutePolicy.ShouldBypassInjectedEvent(0x10, 123, marker));
        Assert.True(WindowsKeyboardRoutePolicy.ShouldBypassInjectedEvent(0x10, marker, marker));
    }

    private static KeyRouteContext Context(string input, string output, bool forward, string reason)
        => new(new KeyInputEvent(input, KeyEventState.Down, "test", DateTimeOffset.UtcNow))
        {
            OutputKey = output,
            ForwardToSystem = forward,
            Reason = reason
        };
}
