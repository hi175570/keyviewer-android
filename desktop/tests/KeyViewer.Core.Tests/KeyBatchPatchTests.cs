using KeyViewer.Core.Models;

namespace KeyViewer.Core.Tests;

public sealed class KeyBatchPatchTests
{
    [Fact]
    public void Only_explicitly_changed_properties_overwrite_selected_keys()
    {
        var first = new KeyDefinition
        {
            BoundKey = "a",
            DisplayLabel = "first",
            Width = 40,
            Height = 50,
            ShowCount = false,
            RainEnabled = false
        };
        var second = new KeyDefinition
        {
            BoundKey = "b",
            DisplayLabel = "second",
            Width = 80,
            Height = 90,
            ShowCount = true,
            RainEnabled = true
        };

        var count = new KeyBatchPatch { Width = 120, ShowCount = false }
            .Apply([first, second]);

        Assert.Equal(2, count);
        Assert.All([first, second], key => Assert.Equal(120, key.Width));
        Assert.All([first, second], key => Assert.False(key.ShowCount));
        Assert.Equal("a", first.BoundKey);
        Assert.Equal("b", second.BoundKey);
        Assert.Equal("first", first.DisplayLabel);
        Assert.Equal("second", second.DisplayLabel);
        Assert.Equal(50, first.Height);
        Assert.Equal(90, second.Height);
        Assert.False(first.RainEnabled);
        Assert.True(second.RainEnabled);
    }

    [Fact]
    public void Explicit_empty_string_clears_every_selected_value()
    {
        var keys = new[]
        {
            new KeyDefinition { DisplayLabel = "one", ImageAsset = "one.png" },
            new KeyDefinition { DisplayLabel = "two", ImageAsset = "two.png" }
        };

        new KeyBatchPatch { DisplayLabel = "", ImageAsset = "" }.Apply(keys);

        Assert.All(keys, key => Assert.Empty(key.DisplayLabel));
        Assert.All(keys, key => Assert.Empty(key.ImageAsset));
    }

    [Fact]
    public void Mapping_switch_changes_only_the_enabled_state()
    {
        var keys = new[]
        {
            new KeyDefinition { BoundKey = "a", MappingEnabled = false, MappedKey = "b" },
            new KeyDefinition { BoundKey = "c", MappingEnabled = true, MappedKey = "d" }
        };

        new KeyBatchPatch { MappingEnabled = true }.Apply(keys);

        Assert.All(keys, key => Assert.True(key.MappingEnabled));
        Assert.Equal("b", keys[0].MappedKey);
        Assert.Equal("d", keys[1].MappedKey);
    }

    [Fact]
    public void Counter_offsets_are_batch_editable_independently()
    {
        var keys = new[] { new KeyDefinition(), new KeyDefinition() };

        new KeyBatchPatch
        {
            TextOffsetXDp = 1,
            TextOffsetYDp = 2,
            CountOffsetXDp = 3,
            CountOffsetYDp = 4,
            CountTextSizeSp = 22
        }.Apply(keys);

        Assert.All(keys, key =>
        {
            Assert.Equal(1, key.TextOffsetXDp);
            Assert.Equal(2, key.TextOffsetYDp);
            Assert.Equal(3, key.CountOffsetXDp);
            Assert.Equal(4, key.CountOffsetYDp);
            Assert.Equal(22, key.CountTextSizeSp);
        });
    }

    [Fact]
    public void Text_appearance_is_batch_editable_without_touching_other_values()
    {
        var keys = new[]
        {
            new KeyDefinition { DisplayLabel = "first", TextSizeSp = 12 },
            new KeyDefinition { DisplayLabel = "second", TextSizeSp = 20 }
        };

        new KeyBatchPatch
        {
            AutoFitText = false,
            TextShadowEnabled = true,
            TextShadowColor = unchecked((int)0x80010203),
            TextShadowOffsetXDp = 2,
            TextShadowOffsetYDp = 3,
            TextShadowBlurRadiusDp = 4
        }.Apply(keys);

        Assert.Equal("first", keys[0].DisplayLabel);
        Assert.Equal("second", keys[1].DisplayLabel);
        Assert.Equal(12, keys[0].TextSizeSp);
        Assert.Equal(20, keys[1].TextSizeSp);
        Assert.All(keys, key =>
        {
            Assert.False(key.AutoFitText);
            Assert.True(key.TextShadowEnabled);
            Assert.Equal(unchecked((int)0x80010203), key.TextShadowColor);
            Assert.Equal(2, key.TextShadowOffsetXDp);
            Assert.Equal(3, key.TextShadowOffsetYDp);
            Assert.Equal(4, key.TextShadowBlurRadiusDp);
        });
    }
}
