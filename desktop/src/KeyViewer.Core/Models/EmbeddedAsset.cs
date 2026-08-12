namespace KeyViewer.Core.Models;

public sealed class EmbeddedAsset
{
    public string Name { get; set; } = string.Empty;
    public string Type { get; set; } = string.Empty;
    public string Extension { get; set; } = string.Empty;
    public long Size { get; set; }
    public string Sha256 { get; set; } = string.Empty;
    public string Base64 { get; set; } = string.Empty;
}

public sealed class EmbeddedAssetEnvelope
{
    public int Version { get; set; } = 1;
    public List<EmbeddedAsset> Items { get; set; } = [];
}
