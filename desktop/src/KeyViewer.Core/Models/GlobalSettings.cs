namespace KeyViewer.Core.Models;

public sealed class GlobalSettings
{
    public bool FloatWindowEnabled { get; set; }
    public double FloatWindowScale { get; set; } = 1;
    public int FloatWindowX { get; set; } = 100;
    public int FloatWindowY { get; set; } = 200;
    public int GlobalRainHeightDp { get; set; } = 200;
    public double GlobalRainWidthDp { get; set; } = 100;
    public double GlobalRainCornerRadiusDp { get; set; } = 4;
    public int GlobalRainReserveHeightDp { get; set; } = 10;
    public bool DarkModeEnabled { get; set; }
    public bool LiquidGlassEnabled { get; set; }
    public string BackgroundImageAsset { get; set; } = string.Empty;
    public bool KeyDebounceEnabled { get; set; }
    public int KeyDebounceMillis { get; set; } = 20;
    public bool KeyRestrictionEnabled { get; set; }
    public string KeyRestrictionBlockedKeys { get; set; } = string.Empty;
    public string LinuxInputDevice { get; set; } = string.Empty;
    public string BridgePipeName { get; set; } = "keyviewer.bridge.v1";
    public string AndroidInputMode { get; set; } = "accessibility";
}
