using KeyViewer.Abstractions;

namespace KeyViewer.Core.Input;

public enum WindowsKeyboardRouteAction
{
    PassThrough,
    Suppress,
    Map
}

public readonly record struct WindowsKeyboardRouteDecision(
    WindowsKeyboardRouteAction Action,
    uint TargetVirtualKey);

public static class WindowsKeyboardRoutePolicy
{
    private const uint LowLevelHookInjectedFlag = 0x10;

    public static bool ShouldBypassInjectedEvent(uint flags, nuint extraInfo, nuint selfMarker)
        => (flags & LowLevelHookInjectedFlag) != 0 && extraInfo == selfMarker;

    public static WindowsKeyboardRouteDecision Decide(
        KeyRouteContext route,
        string inputKey,
        bool mappingActive = false)
    {
        ArgumentNullException.ThrowIfNull(route);
        var targetVirtualKey = VirtualKeyNames.ToVirtualKey(route.OutputKey);
        if (mappingActive)
        {
            return new WindowsKeyboardRouteDecision(WindowsKeyboardRouteAction.Map, targetVirtualKey);
        }

        if (route.Reason.Equals("mapped", StringComparison.Ordinal))
        {
            return targetVirtualKey != 0 && !KeyNames.Equals(route.OutputKey, inputKey)
                ? new WindowsKeyboardRouteDecision(WindowsKeyboardRouteAction.Map, targetVirtualKey)
                : new WindowsKeyboardRouteDecision(WindowsKeyboardRouteAction.PassThrough, 0);
        }

        return route.ForwardToSystem
            ? new WindowsKeyboardRouteDecision(WindowsKeyboardRouteAction.PassThrough, 0)
            : new WindowsKeyboardRouteDecision(WindowsKeyboardRouteAction.Suppress, 0);
    }
}
