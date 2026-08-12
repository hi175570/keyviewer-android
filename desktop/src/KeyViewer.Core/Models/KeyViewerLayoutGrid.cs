namespace KeyViewer.Core.Models;

public static class KeyViewerLayoutGrid
{
    public const double Size = 7.5;
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
