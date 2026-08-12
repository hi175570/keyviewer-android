namespace KeyViewer.Core.Models;

/// <summary>
/// A null property means "leave every selected key unchanged". A non-null
/// property is intentionally applied to all selected keys, including empty
/// strings and false values.
/// </summary>
public sealed class KeyBatchPatch
{
    public string? BoundKey { get; init; }
    public string? DisplayLabel { get; init; }
    public double? TextSizeSp { get; init; }
    public double? CountTextSizeSp { get; init; }
    public bool? AutoFitText { get; init; }
    public bool? TextShadowEnabled { get; init; }
    public int? TextShadowColor { get; init; }
    public double? TextShadowOffsetXDp { get; init; }
    public double? TextShadowOffsetYDp { get; init; }
    public double? TextShadowBlurRadiusDp { get; init; }
    public string? FontAsset { get; init; }
    public string? ImageAsset { get; init; }
    public bool? ImageReplacesLabel { get; init; }
    public bool? ShowCount { get; init; }
    public bool? MappingEnabled { get; init; }
    public string? MappedKey { get; init; }
    public bool? GhostKey { get; init; }
    public double? Width { get; init; }
    public double? Height { get; init; }
    public double? CornerRadiusDp { get; init; }
    public double? BorderWidthDp { get; init; }
    public int? FillColorUp { get; init; }
    public int? BorderColorUp { get; init; }
    public int? TextColorUp { get; init; }
    public int? CountColorUp { get; init; }
    public int? FillColorDown { get; init; }
    public int? BorderColorDown { get; init; }
    public int? TextColorDown { get; init; }
    public int? CountColorDown { get; init; }
    public bool? RainEnabled { get; init; }
    public double? RainOffsetXDp { get; init; }
    public double? RainOffsetYDp { get; init; }
    public double? RainWidthPercent { get; init; }
    public int? RainColor { get; init; }
    public double? RainSpeedDp { get; init; }
    public bool? RainGlowEnabled { get; init; }
    public double? RainGlowRadiusDp { get; init; }
    public bool? KpsTotalNoWrap { get; init; }
    public string? CounterLabelCase { get; init; }
    public double? TextOffsetXDp { get; init; }
    public double? TextOffsetYDp { get; init; }
    public double? CountOffsetXDp { get; init; }
    public double? CountOffsetYDp { get; init; }

    public int Apply(IEnumerable<KeyDefinition> keys)
    {
        ArgumentNullException.ThrowIfNull(keys);
        var count = 0;
        foreach (var key in keys)
        {
            ArgumentNullException.ThrowIfNull(key);
            ApplyTo(key);
            count++;
        }
        return count;
    }

    private void ApplyTo(KeyDefinition key)
    {
        if (BoundKey is not null) key.BoundKey = BoundKey;
        if (DisplayLabel is not null) key.DisplayLabel = DisplayLabel;
        if (TextSizeSp is double textSizeSp) key.TextSizeSp = textSizeSp;
        if (CountTextSizeSp is double countTextSizeSp) key.CountTextSizeSp = countTextSizeSp;
        if (AutoFitText is bool autoFitText) key.AutoFitText = autoFitText;
        if (TextShadowEnabled is bool textShadowEnabled) key.TextShadowEnabled = textShadowEnabled;
        if (TextShadowColor is int textShadowColor) key.TextShadowColor = textShadowColor;
        if (TextShadowOffsetXDp is double textShadowOffsetXDp) key.TextShadowOffsetXDp = textShadowOffsetXDp;
        if (TextShadowOffsetYDp is double textShadowOffsetYDp) key.TextShadowOffsetYDp = textShadowOffsetYDp;
        if (TextShadowBlurRadiusDp is double textShadowBlurRadiusDp) key.TextShadowBlurRadiusDp = textShadowBlurRadiusDp;
        if (FontAsset is not null) key.FontAsset = FontAsset;
        if (ImageAsset is not null) key.ImageAsset = ImageAsset;
        if (ImageReplacesLabel is bool imageReplacesLabel) key.ImageReplacesLabel = imageReplacesLabel;
        if (ShowCount is bool showCount) key.ShowCount = showCount;
        if (MappingEnabled is bool mappingEnabled) key.MappingEnabled = mappingEnabled;
        if (MappedKey is not null) key.MappedKey = MappedKey;
        if (GhostKey is bool ghostKey) key.GhostKey = ghostKey;
        if (Width is double width) key.Width = width;
        if (Height is double height) key.Height = height;
        if (CornerRadiusDp is double cornerRadiusDp) key.CornerRadiusDp = cornerRadiusDp;
        if (BorderWidthDp is double borderWidthDp) key.BorderWidthDp = borderWidthDp;
        if (FillColorUp is int fillColorUp) key.FillColorUp = fillColorUp;
        if (BorderColorUp is int borderColorUp) key.BorderColorUp = borderColorUp;
        if (TextColorUp is int textColorUp) key.TextColorUp = textColorUp;
        if (CountColorUp is int countColorUp) key.CountColorUp = countColorUp;
        if (FillColorDown is int fillColorDown) key.FillColorDown = fillColorDown;
        if (BorderColorDown is int borderColorDown) key.BorderColorDown = borderColorDown;
        if (TextColorDown is int textColorDown) key.TextColorDown = textColorDown;
        if (CountColorDown is int countColorDown) key.CountColorDown = countColorDown;
        if (RainEnabled is bool rainEnabled) key.RainEnabled = rainEnabled;
        if (RainOffsetXDp is double rainOffsetXDp) key.RainOffsetXDp = rainOffsetXDp;
        if (RainOffsetYDp is double rainOffsetYDp) key.RainOffsetYDp = rainOffsetYDp;
        if (RainWidthPercent is double rainWidthPercent) key.RainWidthPercent = rainWidthPercent;
        if (RainColor is int rainColor) key.RainColor = rainColor;
        if (RainSpeedDp is double rainSpeedDp) key.RainSpeedDp = rainSpeedDp;
        if (RainGlowEnabled is bool rainGlowEnabled) key.RainGlowEnabled = rainGlowEnabled;
        if (RainGlowRadiusDp is double rainGlowRadiusDp) key.RainGlowRadiusDp = rainGlowRadiusDp;
        if (KpsTotalNoWrap is bool kpsTotalNoWrap) key.KpsTotalNoWrap = kpsTotalNoWrap;
        if (CounterLabelCase is not null) key.CounterLabelCase = CounterLabelCase;
        if (TextOffsetXDp is double textOffsetXDp) key.TextOffsetXDp = textOffsetXDp;
        if (TextOffsetYDp is double textOffsetYDp) key.TextOffsetYDp = textOffsetYDp;
        if (CountOffsetXDp is double countOffsetXDp) key.CountOffsetXDp = countOffsetXDp;
        if (CountOffsetYDp is double countOffsetYDp) key.CountOffsetYDp = countOffsetYDp;
    }
}
