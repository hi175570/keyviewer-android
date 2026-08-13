using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Config;

public static class ConfigSerializer
{
    public static readonly IReadOnlyList<(string Name, string AssetName)> BundledPresets =
    [
        ("Jipper 16K", "preset_jipper_16k.json"),
        ("Jipper 12K", "preset_jipper_12k.json"),
        ("Jipper 10K", "preset_jipper_10k.json")
    ];
    public const int MaxKeys = 5_000;
    public const int MaxCountEntries = 10_000;
    public const long MaxAssetBytes = 12L * 1024 * 1024;
    public const long MaxTotalAssetBytes = 48L * 1024 * 1024;

    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        NumberHandling = JsonNumberHandling.AllowReadingFromString
    };

    public static KeyViewerDocument Load(string path)
    {
        using var stream = File.OpenRead(path);
        return Load(stream);
    }

    public static KeyViewerDocument Load(Stream stream)
    {
        var document = JsonSerializer.Deserialize<KeyViewerDocument>(stream, Options)
            ?? throw new InvalidDataException("配置内容为空");
        Validate(document);
        foreach (var key in document.Keys.Where(key =>
                     key.MappingEnabled && string.IsNullOrWhiteSpace(key.MappedKey)))
        {
            key.MappingEnabled = false;
        }
        return document;
    }

    public static KeyViewerDocument LoadEmbeddedDefault()
    {
        using var stream = typeof(ConfigSerializer).Assembly
            .GetManifestResourceStream("KeyViewer.Core.Assets.default_config.json")
            ?? throw new InvalidOperationException("内置默认配置不存在");
        return Load(stream);
    }

    public static KeyViewerDocument LoadEmbeddedPreset(string name)
    {
        var preset = BundledPresets.FirstOrDefault(item =>
            item.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        if (string.IsNullOrEmpty(preset.AssetName))
        {
            throw new ArgumentException($"未知的内置预设：{name}", nameof(name));
        }
        using var stream = typeof(ConfigSerializer).Assembly.GetManifestResourceStream(
            "KeyViewer.Core.Assets." + preset.AssetName)
            ?? throw new InvalidOperationException($"内置预设不存在：{name}");
        return Load(stream);
    }

    public static void Save(string path, KeyViewerDocument document)
    {
        Validate(document);
        var directory = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var temporary = path + ".tmp";
        using (var stream = File.Create(temporary))
        {
            JsonSerializer.Serialize(stream, document, Options);
        }

        File.Move(temporary, path, true);
    }

    public static void Save(Stream stream, KeyViewerDocument document)
    {
        ArgumentNullException.ThrowIfNull(stream);
        Validate(document);
        JsonSerializer.Serialize(stream, document, Options);
    }

    public static void SaveConfiguration(string path, KeyViewerDocument document)
    {
        var directory = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var temporary = path + ".tmp";
        using (var stream = File.Create(temporary))
        {
            SaveConfiguration(stream, document);
        }
        File.Move(temporary, path, true);
    }

    public static void SaveConfiguration(Stream stream, KeyViewerDocument document)
    {
        ArgumentNullException.ThrowIfNull(stream);
        Validate(document);
        var root = JsonSerializer.SerializeToNode(document, Options)?.AsObject()
            ?? throw new InvalidDataException("配置序列化失败");
        root.Remove("globalSettings");
        RemoveUnreferencedAssets(root, document);
        JsonSerializer.Serialize(stream, root, Options);
    }

    public static GlobalSettings LoadGlobalSettings(string path, GlobalSettings fallback)
    {
        ArgumentNullException.ThrowIfNull(fallback);
        if (!File.Exists(path))
        {
            return fallback;
        }

        using var stream = File.OpenRead(path);
        var settings = JsonSerializer.Deserialize<GlobalSettings>(stream, Options) ?? fallback;
        ValidateGlobalSettings(settings);
        return settings;
    }

    public static void SaveGlobalSettings(string path, GlobalSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ValidateGlobalSettings(settings);
        var directory = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var temporary = path + ".tmp";
        using (var stream = File.Create(temporary))
        {
            JsonSerializer.Serialize(stream, settings, Options);
        }
        File.Move(temporary, path, true);
    }

    public static void EmbedAsset(KeyViewerDocument document, string filePath, string type)
    {
        var info = new FileInfo(filePath);
        if (!info.Exists)
        {
            throw new FileNotFoundException("素材不存在", filePath);
        }

        if (info.Length > MaxAssetBytes)
        {
            throw new InvalidDataException("单个素材不能超过 12 MiB");
        }

        var currentTotal = document.EmbeddedAssets.Items.Sum(item => item.Size);
        if (currentTotal + info.Length > MaxTotalAssetBytes)
        {
            throw new InvalidDataException("配置素材总量不能超过 48 MiB");
        }

        var bytes = File.ReadAllBytes(filePath);
        var name = info.Name;
        document.EmbeddedAssets.Items.RemoveAll(item =>
            item.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        document.EmbeddedAssets.Items.Add(new EmbeddedAsset
        {
            Name = name,
            Type = type,
            Extension = info.Extension,
            Size = bytes.LongLength,
            Sha256 = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),
            Base64 = Convert.ToBase64String(bytes)
        });
    }

    public static string ExtractAsset(EmbeddedAsset asset, string assetDirectory)
    {
        if (asset.Size < 0 || asset.Size > MaxAssetBytes)
        {
            throw new InvalidDataException($"素材 {asset.Name} 大小非法");
        }

        var safeName = Path.GetFileName(asset.Name);
        if (!string.Equals(safeName, asset.Name, StringComparison.Ordinal))
        {
            throw new InvalidDataException("素材名称包含非法路径");
        }

        var bytes = Convert.FromBase64String(asset.Base64);
        if (bytes.LongLength != asset.Size)
        {
            throw new InvalidDataException($"素材 {asset.Name} 大小校验失败");
        }

        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        if (!hash.Equals(asset.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException($"素材 {asset.Name} SHA-256 校验失败");
        }

        Directory.CreateDirectory(assetDirectory);
        var path = Path.Combine(assetDirectory, safeName);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    public static void Validate(KeyViewerDocument document)
    {
        ArgumentNullException.ThrowIfNull(document);
        document.Keys ??= [];
        document.KeyCounts ??= new(StringComparer.OrdinalIgnoreCase);
        document.GlobalSettings ??= new();
        document.EmbeddedAssets ??= new();
        document.EmbeddedAssets.Items ??= [];

        // Rain speed used to be stored on every key.  Migrate an old layout
        // from its first rain key, then keep the legacy fields synchronized so
        // older Android builds can still import the configuration.
        var legacyRainSpeed = document.Keys.FirstOrDefault(key => key.RainEnabled)?.RainSpeedDp
            ?? document.Keys.FirstOrDefault()?.RainSpeedDp
            ?? 5;
        document.RainSpeedDp = ClampFinite(
            document.HasExplicitRainSpeed ? document.RainSpeedDp : legacyRainSpeed,
            0,
            1_000,
            5);

        if (document.Keys.Count > MaxKeys)
        {
            throw new InvalidDataException($"按键数量超过上限 {MaxKeys}");
        }

        if (document.KeyCounts.Count > MaxCountEntries)
        {
            throw new InvalidDataException($"计数项目超过上限 {MaxCountEntries}");
        }

        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var key in document.Keys)
        {
            if (string.IsNullOrWhiteSpace(key.Id) || !ids.Add(key.Id))
            {
                key.Id = Guid.NewGuid().ToString();
                ids.Add(key.Id);
            }

            key.BoundKey = Limit(key.BoundKey, 128, "未绑定");
            key.MappedKey = Limit(key.MappedKey, 128, string.Empty);
            if (!string.IsNullOrWhiteSpace(key.MappedKey)
                && KeyNames.Equals(key.BoundKey, key.MappedKey))
            {
                key.MappingEnabled = false;
                key.MappedKey = string.Empty;
            }
            key.DisplayLabel = Limit(key.DisplayLabel, 256, string.Empty);
            key.Width = ClampFinite(key.Width, 8, 5_000, 50);
            key.Height = ClampFinite(key.Height, 8, 5_000, 50);
            key.CenterX = ClampFinite(key.CenterX, -100_000, 100_000, 100);
            key.CenterY = ClampFinite(key.CenterY, -100_000, 100_000, 100);
            key.CornerRadiusDp = ClampFinite(key.CornerRadiusDp, 0, 2_500, 8);
            key.BorderWidthDp = ClampFinite(key.BorderWidthDp, 0, 500, 5);
            key.TextSizeSp = ClampFinite(key.TextSizeSp, 4, 300, 14);
            if (key.CountTextSizeSp is double countTextSizeSp)
            {
                key.CountTextSizeSp = ClampFinite(countTextSizeSp, 4, 300, key.TextSizeSp);
            }
            key.TextShadowOffsetXDp = ClampFinite(key.TextShadowOffsetXDp, -500, 500, 1);
            key.TextShadowOffsetYDp = ClampFinite(key.TextShadowOffsetYDp, -500, 500, 1);
            key.TextShadowBlurRadiusDp = ClampFinite(key.TextShadowBlurRadiusDp, 0, 100, 0);
            key.RainWidthPercent = ClampFinite(key.RainWidthPercent, 1, 1_000, 100);
            key.RainSpeedDp = document.RainSpeedDp;
            key.RainGlowRadiusDp = ClampFinite(key.RainGlowRadiusDp, 0, 200, 12);
            key.TextOffsetXDp = ClampFinite(key.TextOffsetXDp != 0 ? key.TextOffsetXDp : key.LegacyKpsTotalLabelOffsetX ?? 0, -5_000, 5_000, 0);
            key.TextOffsetYDp = ClampFinite(key.TextOffsetYDp != 0 ? key.TextOffsetYDp : key.LegacyKpsTotalLabelOffsetY ?? 0, -5_000, 5_000, 0);
            key.CountOffsetXDp = ClampFinite(key.CountOffsetXDp != 0 ? key.CountOffsetXDp : key.LegacyKpsTotalValueOffsetX ?? 0, -5_000, 5_000, 0);
            key.CountOffsetYDp = ClampFinite(key.CountOffsetYDp != 0 ? key.CountOffsetYDp : key.LegacyKpsTotalValueOffsetY ?? 0, -5_000, 5_000, 0);
            key.LegacyKpsTotalLabelOffsetX = null;
            key.LegacyKpsTotalLabelOffsetY = null;
            key.LegacyKpsTotalValueOffsetX = null;
            key.LegacyKpsTotalValueOffsetY = null;
            key.CounterLabelCase = key.CounterLabelCase?.ToLowerInvariant() switch
            {
                "lower" => "lower",
                "title" => "title",
                _ => "upper"
            };
        }

        foreach (var name in document.KeyCounts.Keys.ToArray())
        {
            document.KeyCounts[name] = Math.Max(0, document.KeyCounts[name]);
        }
        // Per-key counts are the source of truth.  Runtime input may maintain
        // TotalPressCount incrementally for immediate display, but every load,
        // configuration edit and save must repair stale/imported totals.
        document.RecalculateTotalPressCount();

        ValidateGlobalSettings(document.GlobalSettings);

        var totalAssetBytes = document.EmbeddedAssets.Items.Sum(item => item.Size);
        if (totalAssetBytes > MaxTotalAssetBytes)
        {
            throw new InvalidDataException("配置素材总量超过 48 MiB");
        }
    }

    private static string Limit(string? value, int length, string fallback)
    {
        value ??= fallback;
        return value.Length <= length ? value : value[..length];
    }

    private static double ClampFinite(double value, double min, double max, double fallback)
        => double.IsFinite(value) ? Math.Clamp(value, min, max) : fallback;

    private static void ValidateGlobalSettings(GlobalSettings settings)
    {
        settings.FloatWindowScale = ClampFinite(settings.FloatWindowScale, 0.5, 2, 1);
        settings.GlobalRainHeightDp = Math.Clamp(settings.GlobalRainHeightDp, 0, 5_000);
        settings.GlobalRainWidthDp = ClampFinite(settings.GlobalRainWidthDp, 1, 5_000, 100);
        settings.GlobalRainCornerRadiusDp = ClampFinite(settings.GlobalRainCornerRadiusDp, 0, 1_000, 4);
        settings.GlobalRainReserveHeightDp = Math.Clamp(
            settings.GlobalRainReserveHeightDp, 0, settings.GlobalRainHeightDp);
        settings.KeyDebounceMillis = Math.Clamp(settings.KeyDebounceMillis, 1, 200);
        settings.KeyRestrictionBlockedKeys = KeyNames.NormalizeList(settings.KeyRestrictionBlockedKeys);
        settings.BackgroundImageAsset = Limit(settings.BackgroundImageAsset, 128, string.Empty);
        settings.LinuxInputDevice = Limit(settings.LinuxInputDevice, 2_048, string.Empty);
        settings.BridgePipeName = Limit(settings.BridgePipeName, 128, "keyviewer.bridge.v1");
        settings.AndroidInputMode = settings.AndroidInputMode?.Trim().ToLowerInvariant() switch
        {
            "accessibility" => "accessibility",
            "shizuku" => "shizuku",
            "root" => "root",
            _ => "accessibility"
        };
    }

    private static void RemoveUnreferencedAssets(JsonObject root, KeyViewerDocument document)
    {
        if (root["embeddedAssets"]?["items"] is not JsonArray items)
        {
            return;
        }

        var referencedAssets = document.Keys
            .SelectMany(key => new[] { key.ImageAsset, key.FontAsset })
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        for (var index = items.Count - 1; index >= 0; index--)
        {
            var name = items[index]?["name"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(name) || !referencedAssets.Contains(name))
            {
                items.RemoveAt(index);
            }
        }
    }
}
