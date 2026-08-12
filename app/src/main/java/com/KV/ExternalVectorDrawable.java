package com.KV;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import androidx.core.graphics.PathParser;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws an Android VectorDrawable stored as ordinary, uncompiled XML. */
final class ExternalVectorDrawable extends Drawable {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private final float viewportWidth;
    private final float viewportHeight;
    private final int intrinsicWidth;
    private final int intrinsicHeight;
    private final GroupNode root;
    private final float rootAlpha;
    private int drawableAlpha = 255;
    private ColorFilter colorFilter;

    private ExternalVectorDrawable(float viewportWidth, float viewportHeight,
                                   int intrinsicWidth, int intrinsicHeight,
                                   float rootAlpha, GroupNode root) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.intrinsicWidth = intrinsicWidth;
        this.intrinsicHeight = intrinsicHeight;
        this.rootAlpha = rootAlpha;
        this.root = root;
    }

    static Drawable create(Context context, InputStream input) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            int event;
            do {
                event = parser.next();
            } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);
            if (event != XmlPullParser.START_TAG || !"vector".equals(parser.getName())) {
                throw new IOException("Root element must be <vector>");
            }

            float viewportWidth = positiveFloat(parser, "viewportWidth");
            float viewportHeight = positiveFloat(parser, "viewportHeight");
            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.max(1, Math.round(dimension(parser, "width", viewportWidth, density)));
            int height = Math.max(1, Math.round(dimension(parser, "height", viewportHeight, density)));
            float alpha = boundedFloat(parser, "alpha", 1f, 0f, 1f);
            GroupNode root = parseChildren(context, parser);
            return new ExternalVectorDrawable(
                    viewportWidth, viewportHeight, width, height, alpha, root);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to parse vector XML", e);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / viewportWidth, bounds.height() / viewportHeight);
        int alpha = Math.round(drawableAlpha * rootAlpha);
        root.draw(canvas, alpha, colorFilter);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        drawableAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicHeight;
    }

    private static GroupNode parseChildren(Context context, XmlPullParser parser) throws Exception {
        int ownerDepth = parser.getDepth();
        GroupNode result = new GroupNode(
                boundedFloat(parser, "rotation", 0f, -360000f, 360000f),
                number(parser, "pivotX", 0f), number(parser, "pivotY", 0f),
                number(parser, "scaleX", 1f), number(parser, "scaleY", 1f),
                number(parser, "translateX", 0f), number(parser, "translateY", 0f));
        while (true) {
            int event = parser.next();
            if (event == XmlPullParser.END_DOCUMENT
                    || (event == XmlPullParser.END_TAG && parser.getDepth() == ownerDepth)) {
                return result;
            }
            if (event != XmlPullParser.START_TAG) continue;
            switch (parser.getName()) {
                case "group":
                    result.children.add(parseChildren(context, parser));
                    break;
                case "path":
                    result.children.add(parsePath(context, parser));
                    break;
                case "clip-path":
                    result.children.add(parseClip(parser));
                    break;
                default:
                    skipTag(parser);
                    break;
            }
        }
    }

    private static VectorNode parsePath(Context context, XmlPullParser parser) throws IOException {
        String pathData = attribute(parser, "pathData");
        Path path = pathData == null ? null : PathParser.createPathFromPathData(pathData);
        if (path == null) throw new IOException("Vector path has invalid pathData");
        if ("evenOdd".equalsIgnoreCase(attribute(parser, "fillType"))) {
            path.setFillType(Path.FillType.EVEN_ODD);
        }
        Paint.Style fillStyle = Paint.Style.FILL;
        int fillColor = parseColor(context, attribute(parser, "fillColor"), Color.BLACK);
        float fillAlpha = boundedFloat(parser, "fillAlpha", 1f, 0f, 1f);
        int strokeColor = parseColor(context, attribute(parser, "strokeColor"), Color.TRANSPARENT);
        float strokeAlpha = boundedFloat(parser, "strokeAlpha", 1f, 0f, 1f);
        float strokeWidth = Math.max(0f, number(parser, "strokeWidth", 0f));
        Paint.Cap cap = parseCap(attribute(parser, "strokeLineCap"));
        Paint.Join join = parseJoin(attribute(parser, "strokeLineJoin"));
        float miter = Math.max(0f, number(parser, "strokeMiterLimit", 4f));
        float trimStart = boundedFloat(parser, "trimPathStart", 0f, 0f, 1f);
        float trimEnd = boundedFloat(parser, "trimPathEnd", 1f, 0f, 1f);
        float trimOffset = number(parser, "trimPathOffset", 0f);
        return new PathNode(path, fillStyle, fillColor, fillAlpha, strokeColor, strokeAlpha,
                strokeWidth, cap, join, miter, trimStart, trimEnd, trimOffset);
    }

    private static VectorNode parseClip(XmlPullParser parser) throws IOException {
        String pathData = attribute(parser, "pathData");
        Path path = pathData == null ? null : PathParser.createPathFromPathData(pathData);
        if (path == null) throw new IOException("Vector clip-path has invalid pathData");
        if ("evenOdd".equalsIgnoreCase(attribute(parser, "fillType"))) {
            path.setFillType(Path.FillType.EVEN_ODD);
        }
        return new ClipNode(path);
    }

    private interface VectorNode {
        void draw(Canvas canvas, int alpha, ColorFilter filter);
    }

    private static final class GroupNode implements VectorNode {
        final List<VectorNode> children = new ArrayList<>();
        final float rotation;
        final float pivotX;
        final float pivotY;
        final float scaleX;
        final float scaleY;
        final float translateX;
        final float translateY;

        GroupNode(float rotation, float pivotX, float pivotY, float scaleX, float scaleY,
                  float translateX, float translateY) {
            this.rotation = rotation;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.translateX = translateX;
            this.translateY = translateY;
        }

        @Override
        public void draw(Canvas canvas, int alpha, ColorFilter filter) {
            int save = canvas.save();
            canvas.translate(translateX + pivotX, translateY + pivotY);
            canvas.rotate(rotation);
            canvas.scale(scaleX, scaleY);
            canvas.translate(-pivotX, -pivotY);
            for (VectorNode child : children) child.draw(canvas, alpha, filter);
            canvas.restoreToCount(save);
        }
    }

    private static final class ClipNode implements VectorNode {
        final Path path;

        ClipNode(Path path) {
            this.path = path;
        }

        @Override
        public void draw(Canvas canvas, int alpha, ColorFilter filter) {
            canvas.clipPath(path);
        }
    }

    private static final class PathNode implements VectorNode {
        final Path source;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float fillAlpha;
        final float strokeAlpha;
        final float trimStart;
        final float trimEnd;
        final float trimOffset;
        final Path trimmed = new Path();
        final PathMeasure measure = new PathMeasure();

        PathNode(Path source, Paint.Style fillStyle, int fillColor, float fillAlpha,
                 int strokeColor, float strokeAlpha, float strokeWidth, Paint.Cap cap,
                 Paint.Join join, float miter, float trimStart, float trimEnd,
                 float trimOffset) {
            this.source = source;
            this.fillAlpha = fillAlpha;
            this.strokeAlpha = strokeAlpha;
            this.trimStart = trimStart;
            this.trimEnd = trimEnd;
            this.trimOffset = trimOffset;
            fill.setStyle(fillStyle);
            fill.setColor(fillColor);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(strokeColor);
            stroke.setStrokeWidth(strokeWidth);
            stroke.setStrokeCap(cap);
            stroke.setStrokeJoin(join);
            stroke.setStrokeMiter(miter);
        }

        @Override
        public void draw(Canvas canvas, int alpha, ColorFilter filter) {
            Path path = trimmedPath();
            fill.setAlpha(combinedAlpha(alpha, fillAlpha, Color.alpha(fill.getColor())));
            fill.setColorFilter(filter);
            if (fill.getAlpha() > 0) canvas.drawPath(path, fill);
            stroke.setAlpha(combinedAlpha(alpha, strokeAlpha, Color.alpha(stroke.getColor())));
            stroke.setColorFilter(filter);
            if (stroke.getStrokeWidth() > 0f && stroke.getAlpha() > 0) {
                canvas.drawPath(path, stroke);
            }
        }

        private Path trimmedPath() {
            if (trimStart == 0f && trimEnd == 1f) return source;
            measure.setPath(source, false);
            float length = measure.getLength();
            float start = floorMod(trimStart + trimOffset, 1f) * length;
            float end = floorMod(trimEnd + trimOffset, 1f) * length;
            trimmed.reset();
            if (start > end) {
                measure.getSegment(start, length, trimmed, true);
                measure.getSegment(0f, end, trimmed, true);
            } else {
                measure.getSegment(start, end, trimmed, true);
            }
            trimmed.rLineTo(0f, 0f);
            return trimmed;
        }
    }

    private static int combinedAlpha(int drawableAlpha, float pathAlpha, int colorAlpha) {
        return Math.max(0, Math.min(255,
                Math.round(drawableAlpha * pathAlpha * colorAlpha / 255f)));
    }

    private static float floorMod(float value, float modulus) {
        return value - (float) Math.floor(value / modulus) * modulus;
    }

    private static Paint.Cap parseCap(String value) {
        if ("round".equalsIgnoreCase(value)) return Paint.Cap.ROUND;
        if ("square".equalsIgnoreCase(value)) return Paint.Cap.SQUARE;
        return Paint.Cap.BUTT;
    }

    private static Paint.Join parseJoin(String value) {
        if ("round".equalsIgnoreCase(value)) return Paint.Join.ROUND;
        if ("bevel".equalsIgnoreCase(value)) return Paint.Join.BEVEL;
        return Paint.Join.MITER;
    }

    private static int parseColor(Context context, String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        value = value.trim();
        if ("none".equalsIgnoreCase(value)) return Color.TRANSPARENT;
        try {
            if (value.startsWith("@")) {
                String resourceName = value.substring(1);
                String packageName = context.getPackageName();
                if (resourceName.startsWith("android:")) {
                    packageName = "android";
                    resourceName = resourceName.substring("android:".length());
                }
                int slash = resourceName.indexOf('/');
                if (slash > 0) {
                    int id = context.getResources().getIdentifier(
                            resourceName.substring(slash + 1), resourceName.substring(0, slash),
                            packageName);
                    if (id != 0) return context.getResources().getColor(id, context.getTheme());
                }
                return fallback;
            }
            return Color.parseColor(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float positiveFloat(XmlPullParser parser, String name) throws IOException {
        float value = number(parser, name, Float.NaN);
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IOException(name + " must be positive");
        }
        return value;
    }

    private static float dimension(XmlPullParser parser, String name, float fallback,
                                   float density) {
        String value = attribute(parser, name);
        if (value == null) return fallback;
        value = value.trim().toLowerCase(Locale.ROOT);
        float multiplier = (value.endsWith("dp") || value.endsWith("dip")) ? density : 1f;
        value = value.replaceAll("(dip|dp|px)$", "");
        try {
            return Float.parseFloat(value) * multiplier;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float boundedFloat(XmlPullParser parser, String name, float fallback,
                                      float min, float max) {
        return Math.max(min, Math.min(max, number(parser, name, fallback)));
    }

    private static float number(XmlPullParser parser, String name, float fallback) {
        String value = attribute(parser, name);
        if (value == null) return fallback;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String attribute(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(ANDROID_NS, name);
        if (value != null) return value;
        value = parser.getAttributeValue(null, name);
        if (value != null) return value;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (name.equals(parser.getAttributeName(i))) return parser.getAttributeValue(i);
        }
        return null;
    }

    private static void skipTag(XmlPullParser parser) throws Exception {
        int depth = parser.getDepth();
        while (!(parser.next() == XmlPullParser.END_TAG && parser.getDepth() == depth)) {
            if (parser.getEventType() == XmlPullParser.END_DOCUMENT) return;
        }
    }
}
