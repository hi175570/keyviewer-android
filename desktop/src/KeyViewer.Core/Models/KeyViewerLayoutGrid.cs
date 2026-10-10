namespace KeyViewer.Core.Models;

public static class KeyViewerLayoutGrid
{
    // The bundled Jipper layouts were authored on a 14.8-DP legacy grid.
    // The detailed editor grid is exactly half of that size.  Rounding this
    // to 7.5 makes the rounding phase cross between the P/= columns and loses
    // one whole cell when an old layout is migrated.
    public const double Size = 7.4;
    private const double LegacySize = 7.5;
    private const double DefaultTolerance = 0.001;

    public static double Snap(double value)
        => Math.Round(value / Size) * Size;

    public static double ClampTranslationToViewport(
        double requested,
        double contentStart,
        double contentEnd,
        double viewportStart,
        double viewportEnd)
    {
        var contentSize = contentEnd - contentStart;
        var viewportSize = viewportEnd - viewportStart;

        // Keep a normal-sized selection wholly visible. An oversized selection
        // may slide across the viewport, but it must continue to overlap it.
        var minimum = contentSize <= viewportSize
            ? viewportStart - contentStart
            : viewportEnd - contentEnd;
        var maximum = contentSize <= viewportSize
            ? viewportEnd - contentEnd
            : viewportStart - contentStart;
        return Math.Clamp(requested, minimum, maximum);
    }

    public static GridAlignmentResult AlignDocument(
        KeyViewerDocument document,
        double tolerance = DefaultTolerance)
    {
        ArgumentNullException.ThrowIfNull(document);
        if (tolerance < 0 || !double.IsFinite(tolerance))
        {
            throw new ArgumentOutOfRangeException(nameof(tolerance));
        }

        if (document.Keys.Count == 0)
        {
            return GridAlignmentResult.None;
        }

        // Move the layout as one unit first. Choosing the top-left visual key as
        // the anchor keeps historical keyboard layouts intact instead of making
        // every key drift independently during migration.
        var anchor = document.Keys
            .OrderBy(key => key.CenterY)
            .ThenBy(key => key.CenterX)
            .First();
        var offsetX = NormalizeOffset(Snap(anchor.CenterX) - anchor.CenterX, tolerance);
        var offsetY = NormalizeOffset(Snap(anchor.CenterY) - anchor.CenterY, tolerance);
        var groupMoved = offsetX != 0 || offsetY != 0;

        if (groupMoved)
        {
            foreach (var key in document.Keys)
            {
                key.CenterX += offsetX;
                key.CenterY += offsetY;
            }
        }

        // Some very old files contain accumulated per-key rounding errors.
        // Correct only those residual errors after the common translation.
        var individuallyAdjusted = 0;
        foreach (var key in document.Keys)
        {
            var snappedX = Snap(key.CenterX);
            var snappedY = Snap(key.CenterY);
            if (Math.Abs(snappedX - key.CenterX) <= tolerance
                && Math.Abs(snappedY - key.CenterY) <= tolerance)
            {
                continue;
            }

            key.CenterX = snappedX;
            key.CenterY = snappedY;
            individuallyAdjusted++;
        }

        return new GridAlignmentResult(
            offsetX,
            offsetY,
            groupMoved ? document.Keys.Count : 0,
            individuallyAdjusted);
    }

    public static int RepairBundledPresetGridMigration(
        KeyViewerDocument document,
        KeyViewerDocument canonicalPreset,
        double tolerance = 0.01)
    {
        ArgumentNullException.ThrowIfNull(document);
        ArgumentNullException.ThrowIfNull(canonicalPreset);
        if (tolerance < 0 || !double.IsFinite(tolerance))
        {
            throw new ArgumentOutOfRangeException(nameof(tolerance));
        }

        if (document.Keys.Count == 0 || document.Keys.Count != canonicalPreset.Keys.Count)
        {
            return 0;
        }

        var currentById = document.Keys
            .GroupBy(key => key.Id, StringComparer.OrdinalIgnoreCase)
            .Where(group => group.Count() == 1)
            .ToDictionary(group => group.Key, group => group.Single(), StringComparer.OrdinalIgnoreCase);
        if (currentById.Count != document.Keys.Count
            || canonicalPreset.Keys.Any(key => !currentById.ContainsKey(key.Id)))
        {
            return 0;
        }

        var canonicalAnchor = canonicalPreset.Keys
            .OrderBy(key => key.CenterY)
            .ThenBy(key => key.CenterX)
            .First();
        var currentAnchor = currentById[canonicalAnchor.Id];

        var matchesLegacyGrid = MatchesPresetShape(
            canonicalPreset.Keys,
            currentById,
            canonicalAnchor,
            currentAnchor,
            LegacySize,
            maximumCellDrift: 1,
            tolerance);
        var matchesCurrentGrid = MatchesPresetShape(
            canonicalPreset.Keys,
            currentById,
            canonicalAnchor,
            currentAnchor,
            Size,
            maximumCellDrift: 0,
            tolerance);
        if (!matchesLegacyGrid && !matchesCurrentGrid)
        {
            return 0;
        }

        var targetAnchorX = Snap(currentAnchor.CenterX);
        var targetAnchorY = Snap(currentAnchor.CenterY);
        var projections = new List<(KeyDefinition Key, double X, double Y)>(document.Keys.Count);
        foreach (var canonicalKey in canonicalPreset.Keys)
        {
            if (!TryGridCells(canonicalKey.CenterX - canonicalAnchor.CenterX, Size, tolerance, out var xCells)
                || !TryGridCells(canonicalKey.CenterY - canonicalAnchor.CenterY, Size, tolerance, out var yCells))
            {
                return 0;
            }

            projections.Add((
                currentById[canonicalKey.Id],
                targetAnchorX + xCells * Size,
                targetAnchorY + yCells * Size));
        }

        var changed = 0;
        foreach (var projection in projections)
        {
            if (Math.Abs(projection.Key.CenterX - projection.X) <= tolerance
                && Math.Abs(projection.Key.CenterY - projection.Y) <= tolerance)
            {
                continue;
            }

            projection.Key.CenterX = projection.X;
            projection.Key.CenterY = projection.Y;
            changed++;
        }
        return changed;
    }

    private static bool MatchesPresetShape(
        IReadOnlyList<KeyDefinition> canonicalKeys,
        IReadOnlyDictionary<string, KeyDefinition> currentById,
        KeyDefinition canonicalAnchor,
        KeyDefinition currentAnchor,
        double sourceGridSize,
        int maximumCellDrift,
        double tolerance)
    {
        foreach (var canonicalKey in canonicalKeys)
        {
            if (!TryGridCells(canonicalKey.CenterX - canonicalAnchor.CenterX, Size, tolerance, out var expectedXCells)
                || !TryGridCells(canonicalKey.CenterY - canonicalAnchor.CenterY, Size, tolerance, out var expectedYCells))
            {
                return false;
            }

            var currentKey = currentById[canonicalKey.Id];
            if (!TryGridCells(currentKey.CenterX - currentAnchor.CenterX, sourceGridSize, tolerance, out var currentXCells)
                || !TryGridCells(currentKey.CenterY - currentAnchor.CenterY, sourceGridSize, tolerance, out var currentYCells)
                || Math.Abs(currentXCells - expectedXCells) > maximumCellDrift
                || Math.Abs(currentYCells - expectedYCells) > maximumCellDrift)
            {
                return false;
            }
        }
        return true;
    }

    private static bool TryGridCells(double offset, double gridSize, double tolerance, out int cells)
    {
        var rounded = Math.Round(offset / gridSize);
        cells = (int)rounded;
        return Math.Abs(offset - rounded * gridSize) <= tolerance;
    }

    private static double NormalizeOffset(double value, double tolerance)
        => Math.Abs(value) <= tolerance ? 0 : value;
}

public readonly record struct GridAlignmentResult(
    double OffsetX,
    double OffsetY,
    int GroupMovedKeyCount,
    int IndividuallyAdjustedKeyCount)
{
    public static GridAlignmentResult None => new(0, 0, 0, 0);
    public bool Changed => GroupMovedKeyCount > 0 || IndividuallyAdjustedKeyCount > 0;
}
