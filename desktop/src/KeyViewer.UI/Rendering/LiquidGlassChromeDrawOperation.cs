using Avalonia;
using Avalonia.Media;
using Avalonia.Rendering.SceneGraph;
using Avalonia.Skia;
using KeyViewer.Abstractions;
using SkiaSharp;

namespace KeyViewer.UI.Rendering;

/// <summary>
/// Draws only the glass tint, shadow and caustic rim. The actual background
/// refraction is supplied underneath the Avalonia surface by the Windows host
/// backdrop compositor.
/// </summary>
internal sealed class LiquidGlassChromeDrawOperation : ICustomDrawOperation
{
    private readonly Rect _surface;
    private readonly double _radius;
    private readonly KeySurfaceStyle _style;

    public LiquidGlassChromeDrawOperation(Rect surface, double radius, KeySurfaceStyle style)
    {
        _surface = surface;
        _radius = radius;
        _style = style;
        Bounds = surface.Inflate(Math.Max(style.ShadowDepth, 1) + 8);
    }

    public Rect Bounds { get; }

    public bool HitTest(Point point) => _surface.Contains(point);

    public bool Equals(ICustomDrawOperation? other) => false;

    public void Dispose()
    {
    }

    public void Render(ImmediateDrawingContext context)
    {
        var leaseFeature = context.TryGetFeature<ISkiaSharpApiLeaseFeature>();
        if (leaseFeature is null)
        {
            return;
        }

        using var lease = leaseFeature.Lease();
        var canvas = lease.SkCanvas;
        var matrix = canvas.TotalMatrix;
        var rect = matrix.MapRect(new SKRect(
            (float)_surface.Left,
            (float)_surface.Top,
            (float)_surface.Right,
            (float)_surface.Bottom));
        var scale = Math.Max(0.01f, Math.Abs(matrix.MapRadius(1)));
        var radius = Math.Min(
            (float)(_radius * scale),
            Math.Min(rect.Width, rect.Height) / 2);
        var saveCount = canvas.Save();
        try
        {
            canvas.ResetMatrix();
            DrawShadow(canvas, rect, radius, scale);
            DrawTint(canvas, rect, radius);
            DrawRim(canvas, rect, radius, scale);
        }
        finally
        {
            canvas.RestoreToCount(saveCount);
        }
    }

    private void DrawShadow(SKCanvas canvas, SKRect rect, float radius, float scale)
    {
        var depth = (float)Math.Max(0, _style.ShadowDepth * scale);
        using var blur = SKMaskFilter.CreateBlur(SKBlurStyle.Normal, Math.Max(0.8f, depth * 0.72f));
        using var paint = new SKPaint
        {
            IsAntialias = true,
            Color = ToSkColor(_style.ShadowColor),
            MaskFilter = blur,
            Style = SKPaintStyle.Fill
        };
        canvas.DrawRoundRect(
            new SKRect(rect.Left, rect.Top + depth * 0.34f, rect.Right, rect.Bottom + depth * 0.34f),
            radius,
            radius,
            paint);
    }

    private void DrawTint(SKCanvas canvas, SKRect rect, float radius)
    {
        var baseColor = ToSkColor(_style.BaseColor);
        var alpha = (byte)Math.Clamp(Math.Round(_style.SurfaceOpacity * 72), 0, 96);
        using var paint = new SKPaint
        {
            IsAntialias = true,
            Color = baseColor.WithAlpha(alpha),
            Style = SKPaintStyle.Fill
        };
        canvas.DrawRoundRect(rect, radius, radius, paint);
    }

    private void DrawRim(SKCanvas canvas, SKRect rect, float radius, float scale)
    {
        using var rim = new SKPaint
        {
            IsAntialias = true,
            Color = ToSkColor(_style.RimLightColor),
            Style = SKPaintStyle.Stroke,
            StrokeWidth = Math.Max(0.8f, (float)(_style.RefractionStrength * scale * 0.15))
        };
        canvas.DrawRoundRect(rect, radius, radius, rim);

        using var caustic = new SKPaint
        {
            IsAntialias = true,
            Color = ToSkColor(_style.GlowColor),
            Style = SKPaintStyle.Stroke,
            StrokeWidth = Math.Max(0.55f, scale * 0.75f)
        };
        var inset = Math.Max(1.2f, scale * 1.6f);
        canvas.DrawArc(
            new SKRect(rect.Left + inset, rect.Top + inset, rect.Right - inset, rect.Bottom - inset),
            196,
            118,
            false,
            caustic);
    }

    private static SKColor ToSkColor(int argb)
    {
        var value = unchecked((uint)argb);
        return new SKColor(
            (byte)(value >> 16),
            (byte)(value >> 8),
            (byte)value,
            (byte)(value >> 24));
    }
}
