using System.Text;

namespace KeyViewer.Core;

public static class KeyNames
{
    private static readonly HashSet<string> Protected = new(StringComparer.OrdinalIgnoreCase)
    {
        "esc", "escape", "lwin", "rwin", "lmeta", "rmeta", "leftwindows", "rightwindows",
        "leftmeta", "rightmeta", "meta"
    };

    private static readonly Dictionary<string, string> Aliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["escape"] = "esc",
        ["back"] = "backspace",
        ["delete"] = "del",
        ["period"] = "dot",
        ["apostrophe"] = "quote",
        ["grave"] = "backquote",
        ["lbracket"] = "bracketleft",
        ["rbracket"] = "bracketright",
        ["return"] = "enter",
        ["spacebar"] = "space",
        ["leftctrl"] = "lctrl",
        ["rightctrl"] = "rctrl",
        ["leftshift"] = "lshift",
        ["rightshift"] = "rshift",
        ["leftalt"] = "lalt",
        ["rightalt"] = "ralt",
        ["lwin"] = "lmeta",
        ["rwin"] = "rmeta",
        ["leftwindows"] = "lmeta",
        ["rightwindows"] = "rmeta",
        ["leftmeta"] = "lmeta",
        ["rightmeta"] = "rmeta",
        [" "] = "space"
    };

    public static string Normalize(string? name)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            return string.Empty;
        }

        var trimmed = name.Trim().ToLowerInvariant();
        return Aliases.TryGetValue(trimmed, out var alias) ? alias : trimmed;
    }

    public static bool Equals(string? left, string? right)
        => string.Equals(Normalize(left), Normalize(right), StringComparison.OrdinalIgnoreCase);

    public static bool IsProtected(string? name) => Protected.Contains(Normalize(name));

    public static HashSet<string> ParseList(string? value)
    {
        return (value ?? string.Empty)
            .Split([',', ';', '\n', '\r', '\t', ' '], StringSplitOptions.RemoveEmptyEntries)
            .Select(Normalize)
            .Where(name => name.Length > 0 && !IsProtected(name))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
    }

    public static string NormalizeList(string? value)
        => string.Join(", ", ParseList(value).OrderBy(key => key, StringComparer.OrdinalIgnoreCase));
}
