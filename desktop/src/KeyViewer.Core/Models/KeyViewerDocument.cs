using System.Text.Json.Serialization;

namespace KeyViewer.Core.Models;

public sealed class KeyViewerDocument
{
    private double _rainSpeedDp = 5;

    public int FormatVersion { get; set; } = 4;
    public GlobalSettings GlobalSettings { get; set; } = new();
    public double RainSpeedDp
    {
        get => _rainSpeedDp;
        set
        {
            _rainSpeedDp = value;
            HasExplicitRainSpeed = true;
        }
    }
    [JsonIgnore]
    public bool HasExplicitRainSpeed { get; private set; }
    public List<KeyDefinition> Keys { get; set; } = [];
    public Dictionary<string, int> KeyCounts { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public int TotalPressCount { get; set; }
    public EmbeddedAssetEnvelope EmbeddedAssets { get; set; } = new();

    public int RecalculateTotalPressCount()
    {
        long total = 0;
        foreach (var count in KeyCounts.Values)
        {
            total += Math.Max(0, count);
            if (total >= int.MaxValue)
            {
                total = int.MaxValue;
                break;
            }
        }

        TotalPressCount = (int)total;
        return TotalPressCount;
    }

    public KeyDefinition? FindKey(string name)
    {
        return Keys
            .OrderByDescending(key => key.ZIndex)
            .FirstOrDefault(key => KeyNames.Equals(key.BoundKey, name));
    }
}
