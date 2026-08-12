using System.Text.Json.Serialization;

namespace KeyViewer.Core.Models;

public sealed class KeyDefinition
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public double CenterX { get; set; } = 100;
    public double CenterY { get; set; } = 100;
    public double Width { get; set; } = 50;
    public double Height { get; set; } = 50;
    public string BoundKey { get; set; } = "未绑定";
    public string DisplayLabel { get; set; } = string.Empty;
    public double CornerRadiusDp { get; set; } = 8;
    public double BorderWidthDp { get; set; } = 5;
    public int FillColorUp { get; set; } = unchecked((int)0xFF999999);
    public int BorderColorUp { get; set; } = unchecked((int)0xFF666666);
    public int TextColorUp { get; set; } = -1;
    public int CountColorUp { get; set; } = -1;
    public int FillColorDown { get; set; } = -1;
    public int BorderColorDown { get; set; } = unchecked((int)0xFF666666);
    public int TextColorDown { get; set; } = -1;
    public int CountColorDown { get; set; } = -1;
    public double TextSizeSp { get; set; } = 14;
    public double? CountTextSizeSp { get; set; }
    public bool AutoFitText { get; set; } = true;
    public bool TextShadowEnabled { get; set; }
    public int TextShadowColor { get; set; } = unchecked((int)0x80000000);
    public double TextShadowOffsetXDp { get; set; } = 1;
    public double TextShadowOffsetYDp { get; set; } = 1;
    public double TextShadowBlurRadiusDp { get; set; }
    public bool ShowCount { get; set; } = true;
    public string FontAsset { get; set; } = string.Empty;
    public string ImageAsset { get; set; } = string.Empty;
    public bool ImageReplacesLabel { get; set; } = true;
    public bool MappingEnabled { get; set; }
    public string MappedKey { get; set; } = string.Empty;
    public bool GhostKey { get; set; }
    public bool RainEnabled { get; set; }
    public double RainOffsetXDp { get; set; }
    public double RainOffsetYDp { get; set; }
    public double RainWidthPercent { get; set; } = 100;
    public int RainColor { get; set; } = -1;
    public double RainSpeedDp { get; set; } = 5;
    public bool RainGlowEnabled { get; set; }
    public double RainGlowRadiusDp { get; set; } = 12;
    public bool KpsTotalNoWrap { get; set; }
    public string CounterLabelCase { get; set; } = "upper";
    public double TextOffsetXDp { get; set; }
    public double TextOffsetYDp { get; set; }
    public double CountOffsetXDp { get; set; }
    public double CountOffsetYDp { get; set; }
    [JsonPropertyName("kpsTotalLabelOffsetX")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? LegacyKpsTotalLabelOffsetX { get; set; }
    [JsonPropertyName("kpsTotalLabelOffsetY")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? LegacyKpsTotalLabelOffsetY { get; set; }
    [JsonPropertyName("kpsTotalValueOffsetX")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? LegacyKpsTotalValueOffsetX { get; set; }
    [JsonPropertyName("kpsTotalValueOffsetY")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? LegacyKpsTotalValueOffsetY { get; set; }
    public int ZIndex { get; set; }

    public string DisplayText => string.IsNullOrWhiteSpace(DisplayLabel) ? BoundKey : DisplayLabel;
    public bool IsCounter => DisplayLabel.Equals("kps", StringComparison.OrdinalIgnoreCase)
        || DisplayLabel.Equals("total", StringComparison.OrdinalIgnoreCase);

    public KeyDefinition Clone()
    {
        var clone = (KeyDefinition)MemberwiseClone();
        clone.Id = Guid.NewGuid().ToString();
        return clone;
    }
}
