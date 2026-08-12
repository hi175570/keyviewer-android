using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using KeyViewer.Core.Config;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Tests;

public sealed class ConfigSerializerTests
{
    [Fact]
    public void Android_default_config_loads_without_schema_conversion()
    {
        var document = ConfigSerializer.LoadEmbeddedDefault();

        Assert.Equal(4, document.FormatVersion);
        Assert.Equal(12, document.Keys.Count);
        Assert.NotEmpty(document.EmbeddedAssets.Items);
        Assert.Contains(document.Keys, key => key.RainEnabled);
        Assert.DoesNotContain(document.Keys, key => key.MappingEnabled);
    }

    [Fact]
    public void Embedded_asset_is_verified_before_extraction()
    {
        var document = ConfigSerializer.LoadEmbeddedDefault();
        var asset = document.EmbeddedAssets.Items[0];
        var directory = Path.Combine(Path.GetTempPath(), "keyviewer-tests", Guid.NewGuid().ToString());
        try
        {
            var path = ConfigSerializer.ExtractAsset(asset, directory);
            var bytes = File.ReadAllBytes(path);
            Assert.Equal(asset.Size, bytes.LongLength);
            Assert.Equal(asset.Sha256, Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant());
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }

    [Fact]
    public void Legacy_empty_and_self_mappings_are_disabled_when_loaded()
    {
        const string json = """
            {
              "keys": [
                { "id": "self", "boundKey": "a", "mappingEnabled": true, "mappedKey": "a" },
                { "id": "empty", "boundKey": "b", "mappingEnabled": true, "mappedKey": "" },
                { "id": "real", "boundKey": "c", "mappingEnabled": true, "mappedKey": "d" }
              ]
            }
            """;
        using var stream = new MemoryStream(Encoding.UTF8.GetBytes(json));

        var document = ConfigSerializer.Load(stream);

        Assert.False(document.Keys[0].MappingEnabled);
        Assert.Empty(document.Keys[0].MappedKey);
        Assert.False(document.Keys[1].MappingEnabled);
        Assert.True(document.Keys[2].MappingEnabled);
        Assert.Equal("d", document.Keys[2].MappedKey);
    }

    [Fact]
    public void Legacy_per_key_rain_speed_migrates_to_configuration_speed()
    {
        const string json = """
            { "keys": [
              { "id": "rain", "boundKey": "a", "rainEnabled": true, "rainSpeedDp": 7.5 },
              { "id": "other", "boundKey": "b", "rainSpeedDp": 2 }
            ] }
            """;
        using var stream = new MemoryStream(Encoding.UTF8.GetBytes(json));

        var document = ConfigSerializer.Load(stream);

        Assert.Equal(7.5, document.RainSpeedDp);
        Assert.All(document.Keys, key => Assert.Equal(7.5, key.RainSpeedDp));
    }

    [Fact]
    public void Rain_speed_is_saved_per_configuration()
    {
        var document = ConfigSerializer.LoadEmbeddedDefault();
        document.RainSpeedDp = 9.5;
        using var stream = new MemoryStream();

        ConfigSerializer.SaveConfiguration(stream, document);

        stream.Position = 0;
        using var json = JsonDocument.Parse(stream);
        Assert.Equal(9.5, json.RootElement.GetProperty("rainSpeedDp").GetDouble());
        Assert.False(json.RootElement.TryGetProperty("globalSettings", out _));
    }

    [Fact]
    public void Loading_a_configuration_recalculates_total_from_key_counts()
    {
        const string json = """
            {
              "keyCounts": { "a": 12, "b": 30, "invalid": -99 },
              "totalPressCount": 999999,
              "keys": []
            }
            """;
        using var stream = new MemoryStream(Encoding.UTF8.GetBytes(json));

        var document = ConfigSerializer.Load(stream);

        Assert.Equal(42, document.TotalPressCount);
        Assert.Equal(0, document.KeyCounts["invalid"]);
    }

    [Fact]
    public void Saving_a_configuration_recalculates_stale_total_and_saturates()
    {
        var document = new KeyViewerDocument
        {
            TotalPressCount = 1,
            KeyCounts = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
            {
                ["a"] = int.MaxValue,
                ["b"] = 50
            }
        };
        using var stream = new MemoryStream();

        ConfigSerializer.SaveConfiguration(stream, document);

        Assert.Equal(int.MaxValue, document.TotalPressCount);
        stream.Position = 0;
        using var json = JsonDocument.Parse(stream);
        Assert.Equal(int.MaxValue, json.RootElement.GetProperty("totalPressCount").GetInt32());
    }

    [Fact]
    public void Configuration_save_omits_global_settings_and_global_only_asset()
    {
        var document = ConfigSerializer.LoadEmbeddedDefault();
        document.GlobalSettings.DarkModeEnabled = true;
        document.GlobalSettings.BackgroundImageAsset = "global-background.png";
        document.EmbeddedAssets.Items.Add(new EmbeddedAsset
        {
            Name = "global-background.png",
            Type = "image",
            Extension = ".png",
            Size = 1,
            Sha256 = "00",
            Base64 = "AA=="
        });
        using var stream = new MemoryStream();

        ConfigSerializer.SaveConfiguration(stream, document);

        stream.Position = 0;
        using var json = JsonDocument.Parse(stream);
        Assert.False(json.RootElement.TryGetProperty("globalSettings", out _));
        var assets = json.RootElement.GetProperty("embeddedAssets").GetProperty("items");
        Assert.DoesNotContain(assets.EnumerateArray(), item =>
            item.GetProperty("name").GetString() == "global-background.png");
    }

    [Fact]
    public void Global_settings_round_trip_in_separate_file()
    {
        var path = Path.Combine(Path.GetTempPath(), "keyviewer-tests",
            Guid.NewGuid().ToString(), "global-settings.json");
        try
        {
            var settings = new GlobalSettings
            {
                DarkModeEnabled = true,
                FloatWindowScale = 1.25,
                KeyRestrictionEnabled = true,
                KeyRestrictionBlockedKeys = "A, B"
            };

            ConfigSerializer.SaveGlobalSettings(path, settings);
            var loaded = ConfigSerializer.LoadGlobalSettings(path, new GlobalSettings());

            Assert.True(loaded.DarkModeEnabled);
            Assert.Equal(1.25, loaded.FloatWindowScale);
            Assert.True(loaded.KeyRestrictionEnabled);
            Assert.Equal("a, b", loaded.KeyRestrictionBlockedKeys);
        }
        finally
        {
            var directory = Path.GetDirectoryName(path)!;
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }

    [Fact]
    public void Counter_offsets_and_logical_font_round_trip_in_layout()
    {
        var document = ConfigSerializer.LoadEmbeddedDefault();
        var key = document.Keys[0];
        key.FontAsset = "system:Microsoft JhengHei";
        key.TextOffsetXDp = 1;
        key.TextOffsetYDp = 2;
        key.CountOffsetXDp = 3;
        key.CountOffsetYDp = 4;
        key.CountTextSizeSp = 22;
        key.CounterLabelCase = "title";
        key.AutoFitText = false;
        key.TextShadowEnabled = true;
        key.TextShadowColor = unchecked((int)0x80010203);
        key.TextShadowOffsetXDp = 5;
        key.TextShadowOffsetYDp = 6;
        key.TextShadowBlurRadiusDp = 7;
        using var stream = new MemoryStream();

        ConfigSerializer.SaveConfiguration(stream, document);
        stream.Position = 0;
        var loaded = ConfigSerializer.Load(stream);

        Assert.Equal("system:Microsoft JhengHei", loaded.Keys[0].FontAsset);
        Assert.Equal(1, loaded.Keys[0].TextOffsetXDp);
        Assert.Equal(2, loaded.Keys[0].TextOffsetYDp);
        Assert.Equal(3, loaded.Keys[0].CountOffsetXDp);
        Assert.Equal(4, loaded.Keys[0].CountOffsetYDp);
        Assert.Equal(22, loaded.Keys[0].CountTextSizeSp);
        Assert.Equal("title", loaded.Keys[0].CounterLabelCase);
        Assert.False(loaded.Keys[0].AutoFitText);
        Assert.True(loaded.Keys[0].TextShadowEnabled);
        Assert.Equal(unchecked((int)0x80010203), loaded.Keys[0].TextShadowColor);
        Assert.Equal(5, loaded.Keys[0].TextShadowOffsetXDp);
        Assert.Equal(6, loaded.Keys[0].TextShadowOffsetYDp);
        Assert.Equal(7, loaded.Keys[0].TextShadowBlurRadiusDp);
    }

    [Fact]
    public void Legacy_counter_offsets_migrate_to_per_key_offsets()
    {
        const string json = """
            { "keys": [ { "id": "legacy", "boundKey": "a",
              "kpsTotalLabelOffsetX": 5, "kpsTotalLabelOffsetY": 6,
              "kpsTotalValueOffsetX": 7, "kpsTotalValueOffsetY": 8 } ] }
            """;
        using var stream = new MemoryStream(System.Text.Encoding.UTF8.GetBytes(json));

        var loaded = ConfigSerializer.Load(stream);

        Assert.Equal(5, loaded.Keys[0].TextOffsetXDp);
        Assert.Equal(6, loaded.Keys[0].TextOffsetYDp);
        Assert.Equal(7, loaded.Keys[0].CountOffsetXDp);
        Assert.Equal(8, loaded.Keys[0].CountOffsetYDp);
    }

    [Theory]
    [InlineData("Jipper 10K", 12)]
    [InlineData("Jipper 12K", 14)]
    [InlineData("Jipper 16K", 18)]
    public void Bundled_Jipper_presets_are_complete_and_portable(string name, int expectedKeys)
    {
        var document = ConfigSerializer.LoadEmbeddedPreset(name);
        Assert.Equal(expectedKeys, document.Keys.Count);
        Assert.Equal(2, document.Keys.Count(key => key.IsCounter));
        Assert.All(document.Keys, key => Assert.True(key.CountTextSizeSp > 0));
        var asset = Assert.Single(document.EmbeddedAssets.Items);
        var directory = Path.Combine(Path.GetTempPath(), "keyviewer-preset-tests", Guid.NewGuid().ToString());
        try
        {
            var path = ConfigSerializer.ExtractAsset(asset, directory);
            Assert.True(File.Exists(path));
            Assert.Equal(asset.Size, new FileInfo(path).Length);
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
    }
}
