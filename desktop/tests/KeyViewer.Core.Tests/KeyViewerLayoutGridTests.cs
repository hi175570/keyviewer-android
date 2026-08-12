using KeyViewer.Core.Models;

namespace KeyViewer.Core.Tests;

public sealed class KeyViewerLayoutGridTests
{
    [Fact]
    public void Historical_layout_is_moved_as_one_group_to_the_nearest_grid()
    {
        var document = DocumentWithKeys(
            (17.25, 32.25),
            (47.25, 62.25),
            (77.25, 92.25));
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
        var document = DocumentWithKeys(
            (17.25, 32.25),
            (48.10, 61.80),
            (76.90, 93.00));

        var result = KeyViewerLayoutGrid.AlignDocument(document);

        Assert.Equal(3, result.GroupMovedKeyCount);
        Assert.Equal(2, result.IndividuallyAdjustedKeyCount);
        Assert.All(document.Keys, AssertOnGrid);
    }

    [Fact]
    public void Already_aligned_layout_is_not_changed()
    {
        var document = DocumentWithKeys((15, 30), (45, 60));

        var result = KeyViewerLayoutGrid.AlignDocument(document);

        Assert.False(result.Changed);
        Assert.Equal(GridAlignmentResult.None, result);
        Assert.Equal(15, document.Keys[0].CenterX);
        Assert.Equal(60, document.Keys[1].CenterY);
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

    private static void AssertOnGrid(KeyDefinition key)
    {
        Assert.Equal(0, key.CenterX % KeyViewerLayoutGrid.Size, 6);
        Assert.Equal(0, key.CenterY % KeyViewerLayoutGrid.Size, 6);
    }
}
