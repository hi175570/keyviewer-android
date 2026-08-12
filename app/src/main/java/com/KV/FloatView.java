package com.KV;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class FloatView extends View {

    private static final int DRAG_THRESHOLD_PX = 10;

    private final WindowManager windowManager;
    private final KeyView keyView;
    private final GlobalSettings settings;

    private WindowManager.LayoutParams layoutParams;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;
    private boolean attached;
    private boolean updating;
    private int contentWidth = 300;
    private int contentHeight = 200;
    private float floatOffsetX;
    private float floatOffsetY;

    public FloatView(Context context, KeyView keyView) {
        super(context);
        this.keyView = keyView;
        this.settings = GlobalSettings.getInstance(context);
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(Color.TRANSPARENT);
    }

    private void setupWindowParams() {
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        measureContent();
        layoutParams = new WindowManager.LayoutParams(
                contentWidth,
                contentHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = settings.floatWindowX;
        layoutParams.y = settings.floatWindowY;
        clampPosition();
    }

    private void measureContent() {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (KeyData key : keyView.getKeyManager().getKeys()) {
            minX = Math.min(minX, key.centerX - key.width / 2f);
            minY = Math.min(minY, key.centerY - key.height / 2f);
            maxX = Math.max(maxX, key.centerX + key.width / 2f);
            maxY = Math.max(maxY, key.centerY + key.height / 2f);
        }

        float baseWidth;
        float baseHeight;
        if (minX == Float.MAX_VALUE) {
            baseWidth = 300;
            baseHeight = 200;
            floatOffsetX = 0;
            floatOffsetY = 0;
        } else {
            float density = getResources().getDisplayMetrics().density;
            float padding = 20f;
            float rainHeight = Math.max(0, settings.globalRainHeightDp) * density;
            baseWidth = Math.max(1f, maxX - minX + padding * 2f);
            baseHeight = Math.max(1f, maxY - minY + padding * 2f + rainHeight);
            floatOffsetX = -minX + padding;
            floatOffsetY = rainHeight - minY + padding;
        }

        float scale = safeScale();
        contentWidth = Math.max(1, (int) Math.ceil(baseWidth * scale));
        contentHeight = Math.max(1, (int) Math.ceil(baseHeight * scale));
    }

    private float safeScale() {
        float scale = settings.floatWindowScale;
        if (!Float.isFinite(scale)) {
            scale = 1f;
        }
        return Math.max(0.5f, Math.min(2f, scale));
    }

    public void attachToWindow() {
        if (attached) {
            return;
        }
        keyView.setEditable(false);
        keyView.startRefreshing();
        setupWindowParams();
        windowManager.addView(this, layoutParams);
        attached = true;
    }

    public void updatePositionFromSettings() {
        if (!attached || layoutParams == null) {
            return;
        }
        layoutParams.x = settings.floatWindowX;
        layoutParams.y = settings.floatWindowY;
        clampPosition();
        windowManager.updateViewLayout(this, layoutParams);
    }

    public void detachFromWindow() {
        keyView.stopRefreshing();
        keyView.releaseAllPressedKeys();
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (IllegalArgumentException ignored) {
            // The system may already have removed the view.
        } finally {
            attached = false;
        }
    }

    public KeyView getKeyView() {
        return keyView;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(contentWidth, contentHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.TRANSPARENT);
        canvas.save();
        canvas.scale(safeScale(), safeScale());
        canvas.translate(floatOffsetX, floatOffsetY);
        keyView.render(canvas, false);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastTouchX;
                float dy = y - lastTouchY;
                if (!dragging && (Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX)) {
                    dragging = true;
                }
                if (dragging && attached) {
                    layoutParams.x += Math.round(dx);
                    layoutParams.y += Math.round(dy);
                    clampPosition();
                    windowManager.updateViewLayout(this, layoutParams);
                }
                lastTouchX = x;
                lastTouchY = y;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && layoutParams != null) {
                    settings.floatWindowX = layoutParams.x;
                    settings.floatWindowY = layoutParams.y;
                    settings.save();
                }
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void clampPosition() {
        if (layoutParams == null) {
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        int visible = Math.round(48f * metrics.density);
        layoutParams.x = Math.max(-contentWidth + visible,
                Math.min(metrics.widthPixels - visible, layoutParams.x));
        layoutParams.y = Math.max(-contentHeight + visible,
                Math.min(metrics.heightPixels - visible, layoutParams.y));
    }

    public void updateScale() {
        if (updating) {
            return;
        }
        updating = true;
        try {
            measureContent();
            if (layoutParams != null) {
                layoutParams.width = contentWidth;
                layoutParams.height = contentHeight;
                clampPosition();
                if (attached) {
                    windowManager.updateViewLayout(this, layoutParams);
                }
            }
            requestLayout();
            invalidate();
        } finally {
            updating = false;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScale();
    }
}
