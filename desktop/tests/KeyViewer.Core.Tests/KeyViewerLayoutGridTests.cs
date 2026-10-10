using KeyViewer.Core.Config;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Tests;

public sealed class KeyViewerLayoutGridTests
{
    [Fact]
    public void Historical_layout_is_moved_as_one_group_to_the_nearest_grid()
    {
        var grid = KeyViewerLayoutGrid.Size;
        var document = DocumentWithKeys(
            (grid * 2 + 2.25, grid * 4 + 2.25),
            (grid * 6 + 2.25, grid * 8 + 2.25),
            (grid * 10 + 2.25, grid * 12 + 2.25));
        var originalDifferences = document.Keys
            .Skip(1)
            .Select(key => (key.CenterX - document.Keys[0].CenterX, key.CenterY - document.Keys[0].CenterY))
            .ToArray();

        var result = KeyViewerLayoutGrid.AlignDocument(document);

        Assert.True(result.Changed);
        Assert.Equal(-2.25, result.OffsetX, 6);
        Assert.Equal(-2.25, result.OffsetY, 6);
        Assert.Equal(3, result.GroupMovedKeyCount);
        Assert.Equal(0, result.IndividuallyAdjustedKeyCount);
        Assert.All(document.Keys, AssertOnGrid);
        Assert.Equal(originalDifferences, document.Keys
            .Skip(1)
            .Select(key => (key.CenterX - document.Keys[0].CenterX, key.CenterY - document.Keys[0].CenterY))
            .ToArray());
    }

    [Fact]
    public void Mixed_legacy_rounding_is_corrected_after_group_alignment()
    {
        var grid = KeyViewerLayoutGrid.Size;
        var document = DocumentWithKeys(
            (grid * 2 + 2.25, grid * 4 + 2.25),
            (grid * 6 + 3.10, grid * 8 + 1.80),
            (grid * 10 + 1.90, grid * 12 + 3.00));

        var result = KeyViewerLayoutGrid.AlignDocument(document);

        Assert.Equal(3, result.GroupMovedKeyCount);
        Assert.Equal(2, result.IndividuallyAdjustedKeyCount);
        Assert.All(document.Keys, AssertOnGrid);
    }

    [Fact]
    public void Already_aligned_layout_is_not_changed()
    {
        var grid = KeyViewerLayoutGrid.Size;
        var document = DocumentWithKeys((grid * 2, grid * 4), (grid * 6, grid * 8));

        var result = KeyViewerLayoutGrid.AlignDocument(document);

        Assert.False(result.Changed);
        Assert.Equal(GridAlignmentResult.None, result);
        Assert.Equal(grid * 2, document.Keys[0].CenterX);
        Assert.Equal(grid * 8, document.Keys[1].CenterY);
    }

    [Fact]
    public void The_legacy_7_5_migration_that_collapsed_the_middle_column_is_repaired()
    {
        var document = DocumentWithKeys(
            (382.5, 397.5),
            (442.5, 397.5),
            (502.5, 397.5),
            (562.5, 397.5),
            (622.5, 397.5), // P
            (675.0, 397.5), // = was pulled one 7.5 cell to the left
            (735.0, 397.5),
            (795.0, 397.5));

        KeyViewerLayoutGrid.AlignDocument(document);

        Assert.All(document.Keys, AssertOnGrid);
        Assert.Equal(KeyViewerLayoutGrid.Size * 8, document.Keys[5].CenterX - document.Keys[4].CenterX, 6);
    }

    [Fact]
    public void Bundled_preset_on_the_legacy_7_5_grid_is_reprojected_without_a_wide_middle_gap()
    {
        var canonical = ConfigSerializer.LoadEmbeddedPreset("Jipper 16K");
        var legacy = ConfigSerializer.LoadEmbeddedPreset("Jipper 16K");
        ProjectPresetToLegacyGrid(legacy, canonical, anchorX: 390, anchorY: 405);

        var changed = KeyViewerLayoutGrid.RepairBundledPresetGridMigration(legacy, canonical);

        Assert.Equal(18, changed);
        Assert.Equal(392.2, legacy.Keys.Single(key => key.BoundKey == "tab").CenterX, 6);
        Assert.Equal(
            KeyViewerLayoutGrid.Size * 8,
            legacy.Keys.Single(key => key.BoundKey == "equal").CenterX
                - legacy.Keys.Single(key => key.BoundKey == "p").CenterX,
            6);
        Assert.Equal(
            KeyViewerLayoutGrid.Size * 8,
            legacy.Keys.Single(key => key.BoundKey == "dot").CenterX
                - legacy.Keys.Single(key => key.BoundKey == "ralt").CenterX,
            6);
    }

    [Fact]
    public void Bundled_preset_repair_does_not_touch_an_arbitrarily_edited_layout()
    {
        var canonical = ConfigSerializer.LoadEmbeddedPreset("Jipper 16K");
        var edited = ConfigSerializer.LoadEmbeddedPreset("Jipper 16K");
        ProjectPresetToLegacyGrid(edited, canonical, anchorX: 390, anchorY: 405);
        edited.Keys.Single(key => key.BoundKey == "equal").CenterX -= 15;

        var changed = KeyViewerLayoutGrid.RepairBundledPresetGridMigration(edited, canonical);

        Assert.Equal(0, changed);
        Assert.Equal(675, edited.Keys.Single(key => key.BoundKey == "equal").CenterX);
    }

    [Theory]
    [InlineData(500, 520, 620, -200, 800, 180)]
    [InlineData(-1000, 520, 620, -200, 800, -720)]
    [InlineData(0, 520, 620, -200, 800, 0)]
    public void Drag_constraint_uses_the_current_panned_world_viewport(
        double requested,
        double contentStart,
        double contentEnd,
        double viewportStart,
        double viewportEnd,
        double expected)
    {
        var actual = KeyViewerLayoutGrid.ClampTranslationToViewport(
            requested,
            contentStart,
            contentEnd,
            viewportStart,
            viewportEnd);

        Assert.Equal(expected, actual, 6);
    }

    [Theory]
    [InlineData(500, 300)]
    [InlineData(-500, -300)]
    [InlineData(0, 0)]
    public void Oversized_selection_can_slide_without_disappearing(double requested, double expected)
    {
        var actual = KeyViewerLayoutGrid.ClampTranslationToViewport(
            requested,
            contentStart: -300,
            contentEnd: 1300,
            viewportStart: 0,
            viewportEnd: 1000);

        Assert.Equal(expected, actual, 6);
    }

    private static KeyViewerDocument DocumentWithKeys(params (double X, double Y)[] positions)
        => new()
        {
            Keys = positions
                .Select(position => new KeyDefinition { CenterX = position.X, CenterY = position.Y })
                .ToList()
        };

    private static void ProjectPresetToLegacyGrid(
        KeyViewerDocument document,
        KeyViewerDocument canonical,
        double anchorX,
        double anchorY)
    {
        const double legacyGrid = 7.5;
        var canonicalAnchor = canonical.Keys
            .OrderBy(key => key.CenterY)
            .ThenBy(key => key.CenterX)
            .First();
        var canonicalById = canonical.Keys.ToDictionary(key => key.Id, StringComparer.OrdinalIgnoreCase);
        foreach (var key in document.Keys)
        {
            var source = canonicalById[key.Id];
            var xCells = Math.Round((source.CenterX - canonicalAnchor.CenterX) / KeyViewerLayoutGrid.Size);
            var yCells = Math.Round((source.CenterY - canonicalAnchor.CenterY) / KeyViewerLayoutGrid.Size);
            key.CenterX = anchorX + xCells * legacyGrid;
            key.CenterY = anchorY + yCells * legacyGrid;
        }
    }

    private static void AssertOnGrid(KeyDefinition key)
    {
        Assert.Equal(KeyViewerLayoutGrid.Snap(key.CenterX), key.CenterX, 6);
        Assert.Equal(KeyViewerLayoutGrid.Snap(key.CenterY), key.CenterY, 6);
    }
}
