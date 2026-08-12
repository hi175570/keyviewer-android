package com.KV;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KeyView extends View {

    private static final String TAG = "KV_KeyView";

    private int gridSizePx;
    private int defaultKeySizePx;
    private int selectionPaddingPx;
    private int handleSizePx;

    private Matrix canvasMatrix = new Matrix();
    private Matrix inverseMatrix = new Matrix();
    private float[] tempPoints = new float[2];

    private float scaleFactor = 1.0f;
    private float translateX = 0f;
    private float translateY = 0f;
    private static final float MIN_SCALE = 0.3f;
    private static final float MAX_SCALE = 3.0f;
    private static final float DEFAULT_SCALE = 1.0f;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean isLongPressTriggered = false;

    private static final int NONE = 0;
    private static final int DRAG_BODY = 1;
    private static final int DRAG_TOP = 2;
    private static final int DRAG_BOTTOM = 3;
    private static final int DRAG_LEFT = 4;
    private static final int DRAG_RIGHT = 5;
    private static final int PAN_CANVAS = 6;

    private int dragMode = NONE;
    private float lastTouchX;
    private float lastTouchY;
    private float minKeySize;
    private boolean movedDuringTouch = false;

    private Paint gridPaint;
    private Paint keyFillPaint;
    private Paint keyBorderPaint;
    private Paint rainPaint;
    private TextPaint textPaint;
    private TextPaint countPaint;
    private Typeface defaultTypeface = Typeface.DEFAULT_BOLD;
    private Typeface adofaiTypeface = Typeface.DEFAULT_BOLD;
    private final Map<String, Typeface> typefaceCache = new HashMap<>();
    private final Map<String, Drawable> drawableCache = new HashMap<>();
    private final Set<String> failedAssets = new HashSet<>();

    private Drawable selectionBorderDrawable;
    private Drawable handleDrawable;
    private Drawable multiSelectBorderDrawable;

    private KeyManager keyManager;
    private KeyData selectedKey;
    private List<KeyData> selectedKeys = new ArrayList<>();
    private boolean batchEditMode = false;

    // 剪贴板相关字段
    private boolean isPastingMode = false;
    private List<KeyData> pastingKeys = new ArrayList<>();
    private boolean isDraggingPastingKey = false;
    private float lastPastingTouchX;
    private float lastPastingTouchY;

    private RectF handleTopRect = new RectF();
    private RectF handleBottomRect = new RectF();
    private RectF handleLeftRect = new RectF();
    private RectF handleRightRect = new RectF();
    private RectF selectionRect = new RectF();

    private OnSelectionChangedListener selectionListener;
    private OnKeyUpdatedListener keyUpdatedListener;

    private Handler handler = new Handler();

    private boolean spaceZoomLocked = false;
    private boolean drawBackground = true;
    private boolean editable = true;

    private static final int RAIN_UPDATE_INTERVAL = 16;
    private long lastUpdateTime = 0;
    private float rainMaxHeightPx;
    private float rainCutLineY;

    private GlobalSettings globalSettings;
    private int canvasBackgroundColor;
    private boolean refreshing = false;
    private boolean refreshScheduled = false;

    private static final Map<String, String> KEY_DISPLAY_MAP = new HashMap<>();
    static {
        KEY_DISPLAY_MAP.put("lctrl", "L Ctrl");
        KEY_DISPLAY_MAP.put("rctrl", "R Ctrl");
        KEY_DISPLAY_MAP.put("lalt", "L Alt");
        KEY_DISPLAY_MAP.put("ralt", "R Alt");
        KEY_DISPLAY_MAP.put("lshift", "L⇧");
        KEY_DISPLAY_MAP.put("rshift", "R⇧");
        KEY_DISPLAY_MAP.put("enter", "⏎");
        KEY_DISPLAY_MAP.put("tab", "↹");
        KEY_DISPLAY_MAP.put("space", "⎵");
        KEY_DISPLAY_MAP.put("capslock", "⇪");
        KEY_DISPLAY_MAP.put("backspace", "back");
        KEY_DISPLAY_MAP.put("del", "del");
        KEY_DISPLAY_MAP.put("pageup", "pg⇑");
        KEY_DISPLAY_MAP.put("pagedown", "pg⇓");
        KEY_DISPLAY_MAP.put("home", "home");
        KEY_DISPLAY_MAP.put("end", "end");
        KEY_DISPLAY_MAP.put("up", "↑");
        KEY_DISPLAY_MAP.put("down", "↓");
        KEY_DISPLAY_MAP.put("left", "←");
        KEY_DISPLAY_MAP.put("right", "→");
        KEY_DISPLAY_MAP.put("comma", ",");
        KEY_DISPLAY_MAP.put("dot", ".");
        KEY_DISPLAY_MAP.put("semicolon", ";");
        KEY_DISPLAY_MAP.put("slash", "/");
        KEY_DISPLAY_MAP.put("backslash", "\\");
        KEY_DISPLAY_MAP.put("quote", "'");
        KEY_DISPLAY_MAP.put("backquote", "~");
        KEY_DISPLAY_MAP.put("bracketleft", "[");
        KEY_DISPLAY_MAP.put("bracketright", "]");
        KEY_DISPLAY_MAP.put("minus", "-");
        KEY_DISPLAY_MAP.put("equal", "=");
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(boolean hasSelection);
    }

    public interface OnKeyUpdatedListener {
        void onKeyUpdated(KeyData key);
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    public void setOnKeyUpdatedListener(OnKeyUpdatedListener listener) {
        this.keyUpdatedListener = listener;
    }

    public KeyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public KeyView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        globalSettings = GlobalSettings.getInstance(context);
        super.setBackgroundColor(Color.TRANSPARENT);
        
        float density = context.getResources().getDisplayMetrics().density;
        gridSizePx = (int) (15 * density);
        defaultKeySizePx = (int) (50 * density);
        int borderWidthPx = (int) (5 * density);
        selectionPaddingPx = (int) (15 * density);
        handleSizePx = (int) (12 * density);
        minKeySize = handleSizePx + borderWidthPx * 2;

        rainMaxHeightPx = globalSettings.globalRainHeightDp * density;
        rainCutLineY = -rainMaxHeightPx - 10 * density;

        gridPaint = new Paint();
        gridPaint.setColor(context.getResources().getColor(R.color.grid_line));
        gridPaint.setStrokeWidth(1f);

        keyFillPaint = new Paint();
        keyFillPaint.setStyle(Paint.Style.FILL);
        keyFillPaint.setAntiAlias(true);

        keyBorderPaint = new Paint();
        keyBorderPaint.setStyle(Paint.Style.STROKE);
        keyBorderPaint.setAntiAlias(true);

        rainPaint = new Paint();
        rainPaint.setStyle(Paint.Style.FILL);
        rainPaint.setAntiAlias(true);

        textPaint = new TextPaint();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);
        
        countPaint = new TextPaint();
        countPaint.setTextAlign(Paint.Align.CENTER);
        countPaint.setAntiAlias(true);
        countPaint.setFakeBoldText(true);
        
        try {
            defaultTypeface = Typeface.createFromAsset(
                    context.getAssets(), "fonts/maplestory_otf_bold.otf");
        } catch (Exception e) {
            Log.e(TAG, "Unable to load built-in Maplestory font", e);
        }
        try {
            adofaiTypeface = Typeface.createFromAsset(context.getAssets(), "fonts/adofai.ttf");
        } catch (Exception e) {
            Log.e(TAG, "Unable to load built-in ADOFAI font", e);
        }
        textPaint.setTypeface(defaultTypeface);
        countPaint.setTypeface(defaultTypeface);

        selectionBorderDrawable = context.getResources().getDrawable(R.drawable.bg_key_border);
        handleDrawable = context.getResources().getDrawable(R.drawable.bg_handle);
        multiSelectBorderDrawable = context.getResources().getDrawable(R.drawable.bg_multi_select);

        gestureDetector = new GestureDetector(context, new GestureListener());
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());

        keyManager = KeyManager.getInstance(context);
        
        pastingKeys = new ArrayList<>();
        
        applyDarkModeToBackground();
    }
    
    private void applyDarkModeToBackground() {
        canvasBackgroundColor = getThemeBackgroundColor();
    }
    
    public void onDarkModeChanged() {
        animateCanvasBackgroundTo(getThemeBackgroundColor());
        invalidate();
    }

    private int getThemeBackgroundColor() {
        return globalSettings.darkModeEnabled ? 0xFF000000 : getResources().getColor(R.color.background_light);
    }

    private void animateCanvasBackgroundTo(int targetColor) {
        if (canvasBackgroundColor == targetColor) return;

        ValueAnimator animator = ValueAnimator.ofArgb(canvasBackgroundColor, targetColor);
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            canvasBackgroundColor = (int) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setBatchEditMode(boolean enabled) {
        this.batchEditMode = enabled;
        if (!enabled) {
            selectedKeys.clear();
        } else {
            selectedKey = null;
            keyManager.clearSelection();
        }
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(false);
        }
        invalidate();
    }

    public boolean isBatchEditMode() {
        return batchEditMode;
    }

    public List<KeyData> getSelectedKeys() {
        if (batchEditMode) {
            return selectedKeys;
        } else if (selectedKey != null) {
            List<KeyData> list = new ArrayList<>();
            list.add(selectedKey);
            return list;
        }
        return new ArrayList<>();
    }

    public void selectAllKeys() {
        if (!batchEditMode || selectedKeys.isEmpty()) {
            return;
        }
        KeyData baseline = selectedKeys.get(0);
        selectedKeys.clear();
        selectedKeys.add(baseline);
        for (KeyData key : keyManager.getKeys()) {
            if (key != baseline) {
                selectedKeys.add(key);
            }
        }
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(!selectedKeys.isEmpty());
        }
        invalidate();
    }

    public void setDrawBackground(boolean draw) {
        this.drawBackground = draw;
        invalidate();
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        FloatService service = FloatService.getInstance();
        if (service != null && service.getFloatView() != null) {
            service.getFloatView().postInvalidate();
        }
        scheduleRefreshIfNeeded();
    }

    public boolean isDraggingKey() {
        return dragMode == DRAG_BODY || dragMode == DRAG_TOP || dragMode == DRAG_BOTTOM ||
                dragMode == DRAG_LEFT || dragMode == DRAG_RIGHT;
    }

    public KeyManager getKeyManager() {
        return keyManager;
    }

    public KeyData getSelectedKey() {
        return selectedKey;
    }

    public void selectKeyById(String id) {
        if (id == null) {
            clearSelection();
            return;
        }
        
        for (KeyData key : keyManager.getKeys()) {
            if (key.id.equals(id)) {
                if (!batchEditMode) {
                    selectedKey = key;
                    keyManager.setSelectedKey(key.id);
                    updateRectsForSelected();
                    if (selectionListener != null) {
                        selectionListener.onSelectionChanged(true);
                    }
                    if (keyUpdatedListener != null) {
                        keyUpdatedListener.onKeyUpdated(key);
                    }
                    invalidate();
                }
                break;
            }
        }
    }

    public KeyData getKeyById(String id) {
        if (id == null) return null;
        for (KeyData key : keyManager.getKeys()) {
            if (key.id.equals(id)) {
                return key;
            }
        }
        return null;
    }

    public void addNewKey() {
        float worldCenterX = -translateX / scaleFactor + getWidth() / 2f / scaleFactor;
        float worldCenterY = -translateY / scaleFactor + getHeight() / 2f / scaleFactor;
        
        worldCenterX = Math.round(worldCenterX / gridSizePx) * gridSizePx;
        worldCenterY = Math.round(worldCenterY / gridSizePx) * gridSizePx;
        
        KeyData newKey = keyManager.createNewKey(worldCenterX, worldCenterY, 
                defaultKeySizePx, defaultKeySizePx);
        
        if (!batchEditMode) {
            selectedKey = newKey;
            keyManager.setSelectedKey(newKey.id);
        }
        updateRectsForSelected();
        
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(!batchEditMode || !selectedKeys.isEmpty());
        }
        if (keyUpdatedListener != null) {
            keyUpdatedListener.onKeyUpdated(newKey);
        }
        invalidate();
    }

    public void deleteSelectedKey() {
        if (batchEditMode) {
            keyManager.deleteKeys(new ArrayList<>(selectedKeys));
            selectedKeys.clear();
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(false);
            }
        } else if (selectedKey != null) {
            keyManager.deleteKey(selectedKey.id);
            selectedKey = null;
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(false);
            }
        }
        invalidate();
    }

    public void clearSelection() {
        selectedKey = null;
        selectedKeys.clear();
        keyManager.clearSelection();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(false);
        }
        invalidate();
    }

    public void zoomIn() {
        if (spaceZoomLocked) return;
        scaleFactor = Math.min(MAX_SCALE, scaleFactor * 1.1f);
        updateMatrix();
        invalidate();
    }

    public void zoomOut() {
        if (spaceZoomLocked) return;
        scaleFactor = Math.max(MIN_SCALE, scaleFactor * 0.9f);
        updateMatrix();
        invalidate();
    }

    public void zoomReset() {
        if (spaceZoomLocked) return;
        scaleFactor = DEFAULT_SCALE;
        translateX = 0;
        translateY = 0;
        updateMatrix();
        invalidate();
    }

    public boolean isKeyBound(String keyName) {
        if (keyName == null) return false;
        for (KeyData key : keyManager.getKeys()) {
            if (key.boundKey != null && key.boundKey.equals(keyName)) {
                return true;
            }
        }
        return false;
    }

    public String getMappedKey(String originalKey) {
        if (originalKey == null) return null;
        
        for (KeyData key : keyManager.getKeys()) {
            if (key.boundKey != null && key.boundKey.equals(originalKey)) {
                if (key.mappingEnabled && key.mappedKey != null && !key.mappedKey.isEmpty()) {
                    Log.d(TAG, "getMappedKey: " + originalKey + " -> " + key.mappedKey);
                    return key.mappedKey;
                }
                break;
            }
        }
        return originalKey;
    }

    public boolean isGhostKey(String originalKey) {
        if (originalKey == null) return false;
        for (KeyData key : keyManager.getKeys()) {
            if (originalKey.equals(key.boundKey)) {
                return key.ghostKey;
            }
        }
        return false;
    }

    public void notifyKeyPressed(String keyName) {
        if ("space".equals(keyName)) {
            spaceZoomLocked = true;
            handler.postDelayed(() -> spaceZoomLocked = false, 200);
        }
    }

    public void setKeyPressed(String keyCode, boolean pressed) {
        float density = getResources().getDisplayMetrics().density;
        for (KeyData key : keyManager.getKeys()) {
            if (key.boundKey != null && key.boundKey.equals(keyCode)) {
                key.isPressed = pressed;
                
                if (pressed && key.rainEnabled) {
                    key.isKeyPressed = true;
                    long now = SystemClock.elapsedRealtime();
                    if (now - key.lastParticleTime > 8) {
                        key.lastParticleTime = now;
                        float particleX = key.centerX + key.rainOffsetXDp * density;
                        float particleY = key.centerY - key.height / 2f - key.rainOffsetYDp * density;
                        float particleWidth = (key.width + key.borderWidthDp * density)
                                * (key.rainWidthPercent / 100f);
                        KeyData.RainParticle p = new KeyData.RainParticle(
                                particleX, particleY, particleWidth, 
                                key.rainColor, 
                                key.rainSpeedDp * density);
                        float reserveHeight = globalSettings.globalRainReserveHeightDp * density;
                        p.height = Math.min(reserveHeight, rainMaxHeightPx);
                        key.rainParticles.add(p);
                    }
                } else if (!pressed && key.rainEnabled) {
                    key.isKeyPressed = false;
                    for (KeyData.RainParticle p : key.rainParticles) {
                        p.isReleased = true;
                    }
                    if (key.rainParticles.isEmpty()) {
                        float particleX = key.centerX + key.rainOffsetXDp * density;
                        float particleY = key.centerY - key.height / 2f - key.rainOffsetYDp * density;
                        float particleWidth = (key.width + key.borderWidthDp * density)
                                * (key.rainWidthPercent / 100f);
                        KeyData.RainParticle p = new KeyData.RainParticle(
                                particleX, particleY, particleWidth, 
                                key.rainColor, 
                                key.rainSpeedDp * density);
                        float reserveHeight = globalSettings.globalRainReserveHeightDp * density;
                        p.height = Math.min(reserveHeight, rainMaxHeightPx);
                        p.isReleased = true;
                        key.rainParticles.add(p);
                    }
                }
            }
        }
        invalidate();
    }

    public void releaseAllPressedKeys() {
        for (KeyData key : keyManager.getKeys()) {
            if (key.isPressed || key.isKeyPressed) {
                key.isPressed = false;
                key.isKeyPressed = false;
                for (KeyData.RainParticle particle : key.rainParticles) {
                    particle.isReleased = true;
                }
            }
        }
        invalidate();
    }

    public void refreshGlobalSettings() {
        float density = getResources().getDisplayMetrics().density;
        rainMaxHeightPx = Math.max(0, globalSettings.globalRainHeightDp) * density;
        rainCutLineY = -rainMaxHeightPx - 10 * density;
        invalidate();
    }

    private void updateRainParticles() {
        long now = SystemClock.elapsedRealtime();
        if (lastUpdateTime == 0) {
            lastUpdateTime = now;
            return;
        }
        float deltaTime = Math.min(4f, Math.max(0f, (now - lastUpdateTime) / 16f));
        lastUpdateTime = now;
        
        float density = getResources().getDisplayMetrics().density;
        rainMaxHeightPx = globalSettings.globalRainHeightDp * density;
        rainCutLineY = -rainMaxHeightPx - 10 * density;
        
        List<KeyData> keys = keyManager.getKeys();
        for (int i = keys.size() - 1; i >= 0; i--) {
            KeyData key = keys.get(i);
            if (!key.rainEnabled) continue;
            
            if (key.isKeyPressed) {
                for (KeyData.RainParticle p : key.rainParticles) {
                    if (!p.isReleased) {
                        float growth = key.rainSpeedDp * density * deltaTime;
                        p.height = Math.min(rainMaxHeightPx, p.height + growth);
                    }
                }
            }
            
            Iterator<KeyData.RainParticle> iterator = key.rainParticles.iterator();
            while (iterator.hasNext()) {
                KeyData.RainParticle p = iterator.next();
                
                if (p.isReleased) {
                    p.y -= p.speed * deltaTime;
                }
                
                if (p.height >= rainMaxHeightPx) {
                    p.hasTouchedLine = true;
                }
                
                if (p.isReleased && p.y <= p.startY - rainMaxHeightPx) {
                    iterator.remove();
                }
            }
        }
    }

    private void updateMatrix() {
        canvasMatrix.reset();
        canvasMatrix.postTranslate(translateX, translateY);
        canvasMatrix.postScale(scaleFactor, scaleFactor, 0, 0);
        canvasMatrix.invert(inverseMatrix);
    }

    private float[] screenToWorld(float screenX, float screenY) {
        tempPoints[0] = screenX;
        tempPoints[1] = screenY;
        inverseMatrix.mapPoints(tempPoints);
        return tempPoints;
    }

    private void updateRectsForSelected() {
        if (selectedKey == null) return;
        
        float halfW = selectedKey.width / 2f;
        float halfH = selectedKey.height / 2f;
        
        selectedKey.rect.set(
            selectedKey.centerX - halfW,
            selectedKey.centerY - halfH,
            selectedKey.centerX + halfW,
            selectedKey.centerY + halfH
        );
        
        selectionRect.set(
            selectedKey.rect.left - selectionPaddingPx,
            selectedKey.rect.top - selectionPaddingPx,
            selectedKey.rect.right + selectionPaddingPx,
            selectedKey.rect.bottom + selectionPaddingPx
        );
        
        float handleHalf = handleSizePx / 2f;
        float centerX = selectedKey.rect.centerX();
        float centerY = selectedKey.rect.centerY();
        
        handleTopRect.set(centerX - handleHalf, selectedKey.rect.top - handleHalf,
                centerX + handleHalf, selectedKey.rect.top + handleHalf);
        handleBottomRect.set(centerX - handleHalf, selectedKey.rect.bottom - handleHalf,
                centerX + handleHalf, selectedKey.rect.bottom + handleHalf);
        handleLeftRect.set(selectedKey.rect.left - handleHalf, centerY - handleHalf,
                selectedKey.rect.left + handleHalf, centerY + handleHalf);
        handleRightRect.set(selectedKey.rect.right - handleHalf, centerY - handleHalf,
                selectedKey.rect.right + handleHalf, centerY + handleHalf);
    }

    private void updateAllKeyRects() {
        for (KeyData key : keyManager.getKeys()) {
            float halfW = key.width / 2f;
            float halfH = key.height / 2f;
            key.rect.set(
                key.centerX - halfW,
                key.centerY - halfH,
                key.centerX + halfW,
                key.centerY + halfH
            );
        }
        updateRectsForSelected();
    }

    private void snapSelectedToGrid() {
        if (selectedKey == null) return;
        
        selectedKey.centerX = Math.round(selectedKey.centerX / gridSizePx) * gridSizePx;
        selectedKey.centerY = Math.round(selectedKey.centerY / gridSizePx) * gridSizePx;
        
        float halfW = selectedKey.width / 2f;
        float halfH = selectedKey.height / 2f;
        selectedKey.centerX = Math.max(halfW, selectedKey.centerX);
        selectedKey.centerY = Math.max(halfH, selectedKey.centerY);
        
        updateRectsForSelected();
        
        keyManager.saveToPreferences();
        
        if (keyUpdatedListener != null) {
            keyUpdatedListener.onKeyUpdated(selectedKey);
        }
        invalidate();
    }

    private void snapBatchSelectionToGrid() {
        if (!batchEditMode || selectedKeys.isEmpty()) return;

        for (KeyData key : selectedKeys) {
            key.centerX = Math.round(key.centerX / gridSizePx) * gridSizePx;
            key.centerY = Math.round(key.centerY / gridSizePx) * gridSizePx;

            float halfW = key.width / 2f;
            float halfH = key.height / 2f;
            key.centerX = Math.max(halfW, key.centerX);
            key.centerY = Math.max(halfH, key.centerY);
        }

        keyManager.saveToPreferences();
        invalidate();
    }

    private void moveBatchSelection(float worldDx, float worldDy) {
        if (selectedKeys.isEmpty()) return;

        float minAllowedDx = Float.NEGATIVE_INFINITY;
        float maxAllowedDx = Float.POSITIVE_INFINITY;
        float minAllowedDy = Float.NEGATIVE_INFINITY;
        float maxAllowedDy = Float.POSITIVE_INFINITY;

        float worldWidth = getWidth() / scaleFactor;
        float worldHeight = getHeight() / scaleFactor;

        for (KeyData key : selectedKeys) {
            float halfW = key.width / 2f;
            float halfH = key.height / 2f;

            minAllowedDx = Math.max(minAllowedDx, halfW - key.centerX);
            maxAllowedDx = Math.min(maxAllowedDx, worldWidth - halfW - key.centerX);
            minAllowedDy = Math.max(minAllowedDy, halfH - key.centerY);
            maxAllowedDy = Math.min(maxAllowedDy, worldHeight - halfH - key.centerY);
        }

        float clampedDx = Math.max(minAllowedDx, Math.min(worldDx, maxAllowedDx));
        float clampedDy = Math.max(minAllowedDy, Math.min(worldDy, maxAllowedDy));

        for (KeyData key : selectedKeys) {
            key.centerX += clampedDx;
            key.centerY += clampedDy;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMatrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        render(canvas, drawBackground);
    }

    public void render(Canvas canvas, boolean includeBackground) {
        canvas.save();

        if (includeBackground) {
            canvas.drawColor(canvasBackgroundColor);
            drawCustomBackground(canvas);
        }

        canvas.concat(canvasMatrix);

        if (includeBackground) {
            int width = getWidth();
            int height = getHeight();
            float startX = -translateX / scaleFactor;
            float startY = -translateY / scaleFactor;
            float endX = startX + width / scaleFactor;
            float endY = startY + height / scaleFactor;

            int gridStartX = ((int) (startX / gridSizePx) - 1) * gridSizePx;
            int gridStartY = ((int) (startY / gridSizePx) - 1) * gridSizePx;
            int gridEndX = ((int) (endX / gridSizePx) + 2) * gridSizePx;
            int gridEndY = ((int) (endY / gridSizePx) + 2) * gridSizePx;

            for (int x = gridStartX; x <= gridEndX; x += gridSizePx) {
                canvas.drawLine(x, gridStartY, x, gridEndY, gridPaint);
            }
            for (int y = gridStartY; y <= gridEndY; y += gridSizePx) {
                canvas.drawLine(gridStartX, y, gridEndX, y, gridPaint);
            }
        }

        updateAllKeyRects();

        // A key and its rain are one layer group. Draw groups from the lowest
        // z-index to the highest so both parts follow the same layer ranking.
        List<KeyData> sortedKeys = keyManager.getKeysSortedByZIndexAsc();
        for (KeyData key : sortedKeys) {
            if (key.rainEnabled) {
                for (KeyData.RainParticle p : key.rainParticles) {
                    float left = p.x - p.width / 2f;
                    float top = p.y - p.height;
                    float right = p.x + p.width / 2f;
                    float bottom = p.y;
                    drawRainParticle(canvas, key, p, left, top, right, bottom);
                }
            }
            drawKey(canvas, key);
        }

        if (batchEditMode) {
            for (KeyData key : selectedKeys) {
                multiSelectBorderDrawable.setBounds(
                    (int) key.rect.left - 5, (int) key.rect.top - 5,
                    (int) key.rect.right + 5, (int) key.rect.bottom + 5);
                multiSelectBorderDrawable.draw(canvas);
            }
        }

        if (selectedKey != null && !batchEditMode) {
            selectionBorderDrawable.setBounds(
                (int) selectionRect.left, (int) selectionRect.top,
                (int) selectionRect.right, (int) selectionRect.bottom);
            selectionBorderDrawable.draw(canvas);

            handleDrawable.setBounds(
                (int) handleTopRect.left, (int) handleTopRect.top,
                (int) handleTopRect.right, (int) handleTopRect.bottom);
            handleDrawable.draw(canvas);

            handleDrawable.setBounds(
                (int) handleBottomRect.left, (int) handleBottomRect.top,
                (int) handleBottomRect.right, (int) handleBottomRect.bottom);
            handleDrawable.draw(canvas);

            handleDrawable.setBounds(
                (int) handleLeftRect.left, (int) handleLeftRect.top,
                (int) handleLeftRect.right, (int) handleLeftRect.bottom);
            handleDrawable.draw(canvas);

            handleDrawable.setBounds(
                (int) handleRightRect.left, (int) handleRightRect.top,
                (int) handleRightRect.right, (int) handleRightRect.bottom);
            handleDrawable.draw(canvas);
        }

        // 绘制粘贴预览（半透明）
        if (isPastingMode) {
            float density = getResources().getDisplayMetrics().density;
            for (KeyData key : pastingKeys) {
                float cornerRadius = key.cornerRadiusDp * density;
                keyFillPaint.setColor(key.fillColorUp);
                keyFillPaint.setAlpha(102);
                canvas.drawRoundRect(key.rect, cornerRadius, cornerRadius, keyFillPaint);
                keyFillPaint.setAlpha(255);
                keyBorderPaint.setColor(key.borderColorUp);
                keyBorderPaint.setAlpha(102);
                keyBorderPaint.setStrokeWidth(key.borderWidthDp * density);
                canvas.drawRoundRect(key.rect, cornerRadius, cornerRadius, keyBorderPaint);
                keyBorderPaint.setAlpha(255);
            }
        }

        canvas.restore();
    }

    private void drawCustomBackground(Canvas canvas) {
        Drawable drawable = resolveDrawable(globalSettings.backgroundImageAsset);
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) return;

        int intrinsicWidth = Math.max(1, drawable.getIntrinsicWidth());
        int intrinsicHeight = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.max((float) viewWidth / intrinsicWidth,
                (float) viewHeight / intrinsicHeight);
        int drawWidth = Math.max(1, Math.round(intrinsicWidth * scale));
        int drawHeight = Math.max(1, Math.round(intrinsicHeight * scale));
        int left = (viewWidth - drawWidth) / 2;
        int top = (viewHeight - drawHeight) / 2;
        drawable.setBounds(left, top, left + drawWidth, top + drawHeight);
        canvas.save();
        canvas.clipRect(0, 0, viewWidth, viewHeight);
        drawable.draw(canvas);
        canvas.restore();
    }

    private void drawKey(Canvas canvas, KeyData key) {
        float density = getResources().getDisplayMetrics().density;
        float cornerRadius = key.cornerRadiusDp * density;
        
        keyFillPaint.setColor(key.isPressed ? key.fillColorDown : key.fillColorUp);
        canvas.drawRoundRect(key.rect, cornerRadius, cornerRadius, keyFillPaint);

        boolean imageReplacesLabel = false;
        if (key.imageAsset != null && !key.imageAsset.isEmpty()) {
            boolean imageDrawn = drawKeyImage(
                    canvas, key, density, key.imageReplacesLabel && key.showCount);
            imageReplacesLabel = imageDrawn && key.imageReplacesLabel;
        }
        
        keyBorderPaint.setColor(key.isPressed ? key.borderColorDown : key.borderColorUp);
        keyBorderPaint.setStrokeWidth(key.borderWidthDp * density);
        canvas.drawRoundRect(key.rect, cornerRadius, cornerRadius, keyBorderPaint);
        
        textPaint.setColor(key.isPressed ? key.textColorDown : key.textColorUp);
        countPaint.setColor(key.isPressed ? key.countColorDown : key.countColorUp);
        applyTypeface(key.fontAsset);
        applyTextShadow(key, density);

        if (imageReplacesLabel) {
            drawImageCount(canvas, key, density);
            return;
        }
        
        String displayText = getDisplayTextForKey(key);
        if (displayText != null && !displayText.isEmpty()) {
            drawKeyText(canvas, key, displayText, density);
        }
    }

    private void drawRainParticle(Canvas canvas, KeyData key, KeyData.RainParticle particle,
                                  float left, float top, float right, float bottom) {
        float density = getResources().getDisplayMetrics().density;
        float corner = globalSettings.globalRainCornerRadiusDp * density;
        if (key.rainGlowEnabled && key.rainGlowRadiusDp > 0f) {
            int baseAlpha = Color.alpha(particle.color);
            float glow = key.rainGlowRadiusDp * density;
            for (int layer = 3; layer >= 1; layer--) {
                float fraction = layer / 3f;
                float spread = glow * fraction;
                int alpha = Math.max(1, Math.round(baseAlpha * (0.12f / layer)));
                rainPaint.setColor((particle.color & 0x00ffffff) | (alpha << 24));
                canvas.drawRoundRect(left - spread, top - spread, right + spread,
                        bottom + spread, corner + spread, corner + spread, rainPaint);
            }
        }
        rainPaint.setColor(particle.color);
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, rainPaint);
    }

    private boolean drawKeyImage(Canvas canvas, KeyData key, float density,
                                 boolean reserveCountSpace) {
        Drawable drawable = resolveDrawable(key.imageAsset);
        if (drawable == null) return false;
        float inset = Math.max(2f * density, key.borderWidthDp * density + density);
        float left = key.rect.left + inset;
        float top = key.rect.top + inset;
        float right = key.rect.right - inset;
        float bottom = key.rect.bottom - inset
                - (reserveCountSpace ? effectiveCountTextSize(key) * density * 1.25f : 0f);
        if (right <= left || bottom <= top) return false;
        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicWidth > 0 && intrinsicHeight > 0) {
            float scale = Math.min((right - left) / intrinsicWidth, (bottom - top) / intrinsicHeight);
            float width = intrinsicWidth * scale;
            float height = intrinsicHeight * scale;
            float centerX = (left + right) / 2f;
            float centerY = (top + bottom) / 2f;
            left = centerX - width / 2f;
            right = centerX + width / 2f;
            top = centerY - height / 2f;
            bottom = centerY + height / 2f;
        }
        drawable.setBounds(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
        drawable.draw(canvas);
        return true;
    }

    private void drawImageCount(Canvas canvas, KeyData key, float density) {
        if (!key.showCount || key.boundKey == null || key.boundKey.isEmpty()) return;
        countPaint.setTextSize(effectiveCountTextSize(key) * density * 0.8f);
        String count = String.valueOf(keyManager.getKeyCount(key.boundKey));
        if (key.autoFitText) {
            float available = Math.max(1f, key.rect.width()
                    - 2f * key.borderWidthDp * density - 6f * density);
            float measured = countPaint.measureText(count);
            if (measured > available) {
                countPaint.setTextSize(Math.max(4f * density,
                        countPaint.getTextSize() * available / measured));
            }
        }
        Paint.FontMetrics metrics = countPaint.getFontMetrics();
        float baseline = key.rect.bottom - Math.max(2f * density, key.borderWidthDp * density)
                - metrics.descent;
        float countCenterX = constrainTextCenterX(
                countPaint, count, key.rect.centerX() + key.countOffsetXDp * density,
                key, density);
        canvas.drawText(count,
                countCenterX,
                baseline + key.countOffsetYDp * density,
                countPaint);
    }

    private void applyTypeface(String storedName) {
        Typeface typeface = defaultTypeface;
        if (FontReference.ADOFAI.equals(storedName)) {
            typeface = adofaiTypeface;
        } else if (FontReference.SANS.equals(storedName)) {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        } else if (FontReference.SERIF.equals(storedName)) {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        } else if (FontReference.MONOSPACE.equals(storedName)) {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        } else if (storedName != null
                && !storedName.isEmpty()
                && !FontReference.isLogical(storedName)
                && !failedAssets.contains(storedName)) {
            typeface = typefaceCache.get(storedName);
            if (typeface == null) {
                try {
                    java.io.File file = KeyAssetStore.resolve(getContext(), storedName);
                    if (file != null) {
                        typeface = Typeface.createFromFile(file);
                        typefaceCache.put(storedName, typeface);
                    }
                } catch (RuntimeException e) {
                    failedAssets.add(storedName);
                    Log.e(TAG, "Unable to load key font " + storedName, e);
                    typeface = defaultTypeface;
                }
            }
        }
        textPaint.setTypeface(typeface);
        countPaint.setTypeface(typeface);
    }

    private void applyTextShadow(KeyData key, float density) {
        if (!key.textShadowEnabled) {
            textPaint.clearShadowLayer();
            countPaint.clearShadowLayer();
            return;
        }
        float radius = Math.max(0.01f, key.textShadowBlurRadiusDp * density);
        float dx = key.textShadowOffsetXDp * density;
        float dy = key.textShadowOffsetYDp * density;
        textPaint.setShadowLayer(radius, dx, dy, key.textShadowColor);
        countPaint.setShadowLayer(radius, dx, dy, key.textShadowColor);
    }

    private Drawable resolveDrawable(String storedName) {
        if (storedName == null || storedName.isEmpty() || failedAssets.contains(storedName)) return null;
        Drawable drawable = drawableCache.get(storedName);
        if (drawable != null) return drawable;
        try {
            drawable = KeyAssetStore.loadDrawable(getContext(), storedName);
            if (drawable != null) drawableCache.put(storedName, drawable);
            else failedAssets.add(storedName);
            return drawable;
        } catch (java.io.IOException | RuntimeException e) {
            failedAssets.add(storedName);
            Log.e(TAG, "Unable to load key image " + storedName, e);
            return null;
        }
    }

    public void clearAssetCaches() {
        typefaceCache.clear();
        drawableCache.clear();
        failedAssets.clear();
    }

    private void drawKeyText(Canvas canvas, KeyData key, String displayText, float density) {
        String[] lines = displayText.split("\n");
        
        boolean isSpecialKey = key.isSpecialKey();
        float baseTextSize = key.textSizeSp * density;
        float baseCountSize = effectiveCountTextSize(key) * density;
        
        float[] lineTextSizes = new float[lines.length];
        for (int i = 0; i < lines.length; i++) {
            if (isSpecialKey && i == 1) {
                int digitCount = lines[i].length();
                float scale = calculateScaleForDigitCount(digitCount);
                lineTextSizes[i] = baseCountSize * scale;
            } else if (!isSpecialKey && lines.length > 1 && i == 1) {
                int digitCount = lines[i].length();
                float scale = calculateScaleForDigitCount(digitCount);
                lineTextSizes[i] = baseCountSize * scale;
            } else {
                lineTextSizes[i] = baseTextSize;
            }
        }

        if (key.autoFitText) {
            float availableWidth = Math.max(1f, key.rect.width()
                    - 2f * key.borderWidthDp * density - 6f * density);
            for (int i = 0; i < lines.length; i++) {
                Paint paint = (i == 0) ? textPaint : countPaint;
                paint.setTextSize(lineTextSizes[i]);
                float measured = paint.measureText(lines[i]);
                if (measured > availableWidth) {
                    lineTextSizes[i] = Math.max(4f * density,
                            lineTextSizes[i] * availableWidth / measured);
                }
            }
        }
        
        float[] lineHeights = new float[lines.length];
        float[] lineAscents = new float[lines.length];
        float totalHeight = 0;
        for (int i = 0; i < lines.length; i++) {
            Paint paint = (i == 0) ? textPaint : countPaint;
            paint.setTextSize(lineTextSizes[i]);
            Paint.FontMetrics fm = paint.getFontMetrics();
            lineHeights[i] = fm.descent - fm.ascent;
            lineAscents[i] = fm.ascent;
            totalHeight += lineHeights[i];
        }

        if (key.autoFitText) {
            float availableHeight = Math.max(1f, key.rect.height()
                    - 2f * key.borderWidthDp * density - 2f * density);
            if (totalHeight > availableHeight) {
                float scale = availableHeight / totalHeight;
                totalHeight = 0f;
                for (int i = 0; i < lines.length; i++) {
                    lineTextSizes[i] = Math.max(4f * density, lineTextSizes[i] * scale);
                    Paint paint = (i == 0) ? textPaint : countPaint;
                    paint.setTextSize(lineTextSizes[i]);
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    lineHeights[i] = fm.descent - fm.ascent;
                    lineAscents[i] = fm.ascent;
                    totalHeight += lineHeights[i];
                }
            }
        }
        
        float centerY = key.rect.centerY();
        float startY = centerY - totalHeight / 2;
        
        if (isSpecialKey && key.kpsTotalNoWrap) {
            float labelOffsetX = key.textOffsetXDp * density;
            float labelOffsetY = key.textOffsetYDp * density;
            float valueOffsetX = key.countOffsetXDp * density;
            float valueOffsetY = key.countOffsetYDp * density;
            float centerX = key.rect.centerX();
            
            Paint labelPaint = textPaint;
            labelPaint.setTextSize(lineTextSizes[0]);
            Paint.FontMetrics labelFm = labelPaint.getFontMetrics();
            float labelBaseline = centerY - (labelFm.ascent + labelFm.descent) / 2
                    + labelOffsetY;
            float labelCenterX = constrainTextCenterX(
                    labelPaint, lines[0], centerX + labelOffsetX, key, density);
            canvas.drawText(lines[0], labelCenterX, labelBaseline, labelPaint);
            
            if (lines.length > 1) {
                Paint valuePaint = countPaint;
                valuePaint.setTextSize(lineTextSizes[1]);
                Paint.FontMetrics valueFm = valuePaint.getFontMetrics();
                float valueBaseline = centerY - (valueFm.ascent + valueFm.descent) / 2
                        + valueOffsetY;
                // For side-by-side KPS/TOTAL counters the configured X offset is the
                // right-hand anchor. The value therefore grows to the left as its digit
                // count changes instead of overflowing to the right from a fixed center.
                float valueWidth = valuePaint.measureText(lines[1]);
                float valueCenterX = centerX + valueOffsetX - valueWidth / 2f;
                valueCenterX = constrainTextCenterX(
                        valuePaint, lines[1], valueCenterX, key, density);
                canvas.drawText(lines[1], valueCenterX, valueBaseline, valuePaint);
            }
        } else {
            for (int i = 0; i < lines.length; i++) {
                Paint paint = (i == 0) ? textPaint : countPaint;
                paint.setTextSize(lineTextSizes[i]);
                
                float lineStartY = startY;
                for (int j = 0; j < i; j++) {
                    lineStartY += lineHeights[j];
                }
                float offsetX = (i == 0 ? key.textOffsetXDp : key.countOffsetXDp) * density;
                float offsetY = (i == 0 ? key.textOffsetYDp : key.countOffsetYDp) * density;
                float baseline = lineStartY - lineAscents[i] + offsetY;
                
                float lineCenterX = constrainTextCenterX(
                        paint, lines[i], key.rect.centerX() + offsetX, key, density);
                canvas.drawText(lines[i], lineCenterX, baseline, paint);
            }
        }
    }

    private float constrainTextCenterX(Paint paint, String text, float desiredCenterX,
                                       KeyData key, float density) {
        if (!key.autoFitText) return desiredCenterX;

        float shadowExtra = key.textShadowEnabled
                ? (Math.abs(key.textShadowOffsetXDp) + key.textShadowBlurRadiusDp) * density
                : 0f;
        float inset = Math.max(3f * density, key.borderWidthDp * density + 3f * density)
                + shadowExtra;
        float halfWidth = paint.measureText(text) / 2f;
        float minimum = key.rect.left + inset + halfWidth;
        float maximum = key.rect.right - inset - halfWidth;
        if (minimum > maximum) return key.rect.centerX();
        return Math.max(minimum, Math.min(maximum, desiredCenterX));
    }
    
    private float calculateScaleForDigitCount(int digitCount) {
        if (digitCount >= 9) return 0.5f;
        if (digitCount >= 8) return 0.55f;
        if (digitCount >= 7) return 0.6f;
        if (digitCount >= 6) return 0.7f;
        if (digitCount >= 5) return 0.8f;
        if (digitCount >= 4) return 0.9f;
        return 1.0f;
    }

    private static float effectiveCountTextSize(KeyData key) {
        return key.countTextSizeSp > 0f ? key.countTextSizeSp : key.textSizeSp;
    }

    private static String applyCounterCase(String label, String mode) {
        String lower = label.toLowerCase(java.util.Locale.ROOT);
        if ("lower".equals(mode)) return lower;
        if ("title".equals(mode)) {
            return lower.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + lower.substring(1);
        }
        return lower.toUpperCase(java.util.Locale.ROOT);
    }

    private String getDisplayTextForKey(KeyData key) {
        boolean hasCustomLabel = key.displayLabel != null && !key.displayLabel.isEmpty();
        
        if (hasCustomLabel) {
            String lower = key.displayLabel.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("kps")) {
                return applyCounterCase("kps", key.counterLabelCase) + "\n" + keyManager.getKPS();
            } else if (lower.equals("total")) {
                return applyCounterCase("total", key.counterLabelCase) + "\n" + keyManager.getTotalPressCount();
            }
        }
        
        String baseText;
        if (hasCustomLabel) {
            baseText = key.displayLabel;
        } else {
            if (key.boundKey == null || key.boundKey.equals("未绑定") || key.boundKey.isEmpty()) {
                return "";
            }
            String displayName = KEY_DISPLAY_MAP.get(key.boundKey);
            if (displayName == null) {
                displayName = key.boundKey.toUpperCase(java.util.Locale.ROOT);
            }
            baseText = displayName;
        }
        
        if (key.showCount) {
            if (key.boundKey != null && !key.boundKey.equals("未绑定") && !key.boundKey.isEmpty()) {
                int count = keyManager.getKeyCount(key.boundKey);
                return baseText + "\n" + count;
            }
        }
        
        return baseText;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editable) return false;
        
        scaleGestureDetector.onTouchEvent(event);

        float screenX = event.getX();
        float screenY = event.getY();
        float[] world = screenToWorld(screenX, screenY);
        float worldX = world[0];
        float worldY = world[1];

        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 粘贴模式处理
                if (isPastingMode) {
                    this.isDraggingPastingKey = true;
                    this.lastPastingTouchX = screenX;
                    this.lastPastingTouchY = screenY;
                    return true;
                }
                
                dragMode = NONE;
                isLongPressTriggered = false;
                movedDuringTouch = false;

                if (!batchEditMode && selectedKey != null) {
                    if (handleTopRect.contains(worldX, worldY)) {
                        dragMode = DRAG_TOP;
                    } else if (handleBottomRect.contains(worldX, worldY)) {
                        dragMode = DRAG_BOTTOM;
                    } else if (handleLeftRect.contains(worldX, worldY)) {
                        dragMode = DRAG_LEFT;
                    } else if (handleRightRect.contains(worldX, worldY)) {
                        dragMode = DRAG_RIGHT;
                    }
                }

                if (dragMode == NONE) {
                    KeyData hitKey = keyManager.findKeyAt(worldX, worldY);
                    if (hitKey != null) {
                        if (batchEditMode) {
                            if (selectedKeys.contains(hitKey)) {
                                dragMode = DRAG_BODY;
                            } else {
                                selectedKeys.add(hitKey);
                                if (selectionListener != null) {
                                    selectionListener.onSelectionChanged(!selectedKeys.isEmpty());
                                }
                                invalidate();
                            }
                        } else {
                            selectedKey = hitKey;
                            keyManager.setSelectedKey(hitKey.id);
                            keyManager.bringToFront(hitKey.id);
                            updateRectsForSelected();
                            dragMode = DRAG_BODY;
                            
                            if (selectionListener != null) {
                                selectionListener.onSelectionChanged(true);
                            }
                            if (keyUpdatedListener != null) {
                                keyUpdatedListener.onKeyUpdated(hitKey);
                            }
                            invalidate();
                        }
                    } else {
                        if (!batchEditMode && selectedKey != null) {
                            selectedKey = null;
                            keyManager.clearSelection();
                            if (selectionListener != null) {
                                selectionListener.onSelectionChanged(false);
                            }
                            invalidate();
                        }
                    }
                }

                if (dragMode != NONE && (batchEditMode || selectedKey != null)) {
                    lastTouchX = screenX;
                    lastTouchY = screenY;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                // 粘贴模式处理 - 拖动预览按键（流畅跟手，不吸附）
                if (isPastingMode && isDraggingPastingKey) {
                    float dx = screenX - this.lastPastingTouchX;
                    float dy = screenY - this.lastPastingTouchY;
                    float worldDx = dx / scaleFactor;
                    float worldDy = dy / scaleFactor;
                    
                    for (KeyData key : pastingKeys) {
                        key.centerX += worldDx;
                        key.centerY += worldDy;
                    }
                    
                    updatePastingRects();
                    invalidate();
                    
                    this.lastPastingTouchX = screenX;
                    this.lastPastingTouchY = screenY;
                    return true;
                }
                
                if (dragMode == NONE) return true;

                float dx = screenX - lastTouchX;
                float dy = screenY - lastTouchY;

                switch (dragMode) {
                    case PAN_CANVAS:
                        translateX += dx;
                        translateY += dy;
                        updateMatrix();
                        movedDuringTouch = true;
                        break;

                    case DRAG_BODY:
                        if (batchEditMode) {
                            float worldDx = dx / scaleFactor;
                            float worldDy = dy / scaleFactor;
                            moveBatchSelection(worldDx, worldDy);
                            movedDuringTouch = true;
                        } else if (selectedKey != null) {
                            float worldDx = dx / scaleFactor;
                            float worldDy = dy / scaleFactor;
                            selectedKey.centerX += worldDx;
                            selectedKey.centerY += worldDy;
                            
                            float halfW = selectedKey.width / 2f;
                            float halfH = selectedKey.height / 2f;
                            selectedKey.centerX = Math.max(halfW, Math.min(selectedKey.centerX, getWidth() / scaleFactor - halfW));
                            selectedKey.centerY = Math.max(halfH, Math.min(selectedKey.centerY, getHeight() / scaleFactor - halfH));
                            
                            updateRectsForSelected();
                            movedDuringTouch = true;
                        }
                        break;

                    case DRAG_TOP:
                        if (selectedKey != null) {
                            float worldDyTop = dy / scaleFactor;
                            float newHeight = selectedKey.height - worldDyTop;
                            if (newHeight >= minKeySize) {
                                selectedKey.height = newHeight;
                                selectedKey.centerY += worldDyTop / 2f;
                            }
                            updateRectsForSelected();
                            movedDuringTouch = true;
                        }
                        break;

                    case DRAG_BOTTOM:
                        if (selectedKey != null) {
                            float worldDyBottom = dy / scaleFactor;
                            float newHeight = selectedKey.height + worldDyBottom;
                            if (newHeight >= minKeySize) {
                                selectedKey.height = newHeight;
                                selectedKey.centerY += worldDyBottom / 2f;
                            }
                            updateRectsForSelected();
                            movedDuringTouch = true;
                        }
                        break;

                    case DRAG_LEFT:
                        if (selectedKey != null) {
                            float worldDxLeft = dx / scaleFactor;
                            float newWidth = selectedKey.width - worldDxLeft;
                            if (newWidth >= minKeySize) {
                                selectedKey.width = newWidth;
                                selectedKey.centerX += worldDxLeft / 2f;
                            }
                            updateRectsForSelected();
                            movedDuringTouch = true;
                        }
                        break;

                    case DRAG_RIGHT:
                        if (selectedKey != null) {
                            float worldDxRight = dx / scaleFactor;
                            float newWidth = selectedKey.width + worldDxRight;
                            if (newWidth >= minKeySize) {
                                selectedKey.width = newWidth;
                                selectedKey.centerX += worldDxRight / 2f;
                            }
                            updateRectsForSelected();
                            movedDuringTouch = true;
                        }
                        break;
                }

                invalidate();
                lastTouchX = screenX;
                lastTouchY = screenY;
                return true;

            case MotionEvent.ACTION_UP:
                // 粘贴模式处理 - 松开时才吸附到网格
                if (isPastingMode && isDraggingPastingKey) {
                    for (KeyData key : pastingKeys) {
                        key.centerX = Math.round(key.centerX / gridSizePx) * gridSizePx;
                        key.centerY = Math.round(key.centerY / gridSizePx) * gridSizePx;
                    }
                    updatePastingRects();
                    invalidate();
                    this.isDraggingPastingKey = false;
                    return true;
                }
                
                if (dragMode == DRAG_BODY || dragMode == DRAG_TOP || dragMode == DRAG_BOTTOM ||
                        dragMode == DRAG_LEFT || dragMode == DRAG_RIGHT) {
                    if (batchEditMode) {
                        if (!movedDuringTouch) {
                            KeyData hitKey = keyManager.findKeyAt(worldX, worldY);
                            if (hitKey != null && selectedKeys.contains(hitKey)) {
                                selectedKeys.remove(hitKey);
                                if (selectionListener != null) {
                                    selectionListener.onSelectionChanged(!selectedKeys.isEmpty());
                                }
                            }
                        } else {
                            snapBatchSelectionToGrid();
                        }
                    } else {
                        snapSelectedToGrid();
                    }
                    invalidate();
                }
                dragMode = NONE;
                isLongPressTriggered = false;
                movedDuringTouch = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (isPastingMode) {
                    this.isDraggingPastingKey = false;
                    return true;
                }
                dragMode = NONE;
                isLongPressTriggered = false;
                movedDuringTouch = false;
                return true;
        }

        return super.onTouchEvent(event);
    }

    // ==================== 剪贴板相关方法 ====================
    
    public boolean isPastingMode() {
        return isPastingMode;
    }

    public void startPasting() {
        List<KeyData> clipboard = this.keyManager.getClipboard();
        if (clipboard.isEmpty()) return;
        
        this.isPastingMode = true;
        this.pastingKeys.clear();
        
        float avgX = 0f, avgY = 0f;
        for (KeyData key : clipboard) {
            avgX += key.centerX;
            avgY += key.centerY;
        }
        
        float screenCenterX = (-translateX / scaleFactor) + (getWidth() / 2f / scaleFactor);
        float screenCenterY = (-translateY / scaleFactor) + (getHeight() / 2f / scaleFactor);
        float offsetX = screenCenterX - (avgX / clipboard.size());
        float offsetY = screenCenterY - (avgY / clipboard.size());
        
        for (KeyData key : clipboard) {
            KeyData pasted = key.m4clone();
            pasted.centerX += offsetX;
            pasted.centerY += offsetY;
            // 初始位置直接吸附到网格
            pasted.centerX = Math.round(pasted.centerX / gridSizePx) * gridSizePx;
            pasted.centerY = Math.round(pasted.centerY / gridSizePx) * gridSizePx;
            this.pastingKeys.add(pasted);
        }
        updatePastingRects();
        invalidate();
    }

    public void confirmPaste() {
        this.keyManager.addKeysToFront(new ArrayList<>(this.pastingKeys));
        this.isPastingMode = false;
        this.pastingKeys.clear();
        invalidate();
    }

    public void cancelPaste() {
        this.isPastingMode = false;
        this.pastingKeys.clear();
        invalidate();
    }

    private void updatePastingRects() {
        for (KeyData key : this.pastingKeys) {
            float halfW = key.width / 2f;
            float halfH = key.height / 2f;
            key.rect.set(key.centerX - halfW, key.centerY - halfH, 
                         key.centerX + halfW, key.centerY + halfH);
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public void onLongPress(MotionEvent e) {
            if (batchEditMode || isPastingMode) return;
            
            float screenX = e.getX();
            float screenY = e.getY();
            float[] world = screenToWorld(screenX, screenY);
            float worldX = world[0];
            float worldY = world[1];

            KeyData hitKey = keyManager.findKeyAt(worldX, worldY);
            boolean hitHandle = false;
            if (selectedKey != null) {
                hitHandle = handleTopRect.contains(worldX, worldY) ||
                        handleBottomRect.contains(worldX, worldY) ||
                        handleLeftRect.contains(worldX, worldY) ||
                        handleRightRect.contains(worldX, worldY);
            }

            if (hitKey == null && !hitHandle) {
                dragMode = PAN_CANVAS;
                isLongPressTriggered = true;
                lastTouchX = e.getX();
                lastTouchY = e.getY();
            }
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (spaceZoomLocked) return false;
            
            float newScale = scaleFactor * detector.getScaleFactor();
            newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));

            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();

            float scaleChange = newScale / scaleFactor;
            translateX = focusX - scaleChange * (focusX - translateX);
            translateY = focusY - scaleChange * (focusY - translateY);

            scaleFactor = newScale;
            updateMatrix();
            invalidate();
            return true;
        }
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshScheduled = false;
            if (!refreshing) return;
            updateRainParticles();
            invalidate();
        }
    };

    public void startRefreshing() {
        if (refreshing) return;
        refreshing = true;
        lastUpdateTime = 0;
        scheduleRefreshIfNeeded();
    }

    public void stopRefreshing() {
        refreshing = false;
        refreshScheduled = false;
        handler.removeCallbacks(refreshRunnable);
        lastUpdateTime = 0;
    }

    private void scheduleRefreshIfNeeded() {
        if (!refreshing || refreshScheduled) return;
        boolean hasRainAnimation = false;
        boolean hasLiveKps = false;
        for (KeyData key : keyManager.getKeys()) {
            if (!key.rainParticles.isEmpty()) hasRainAnimation = true;
            if (key.displayLabel != null && "kps".equalsIgnoreCase(key.displayLabel)
                    && keyManager.getKPS() > 0) {
                hasLiveKps = true;
            }
        }
        if (!hasRainAnimation && !hasLiveKps) return;
        refreshScheduled = true;
        // The overlay renders this KeyView into FloatView without attaching the
        // KeyView itself to a window. View.postDelayed() queues work until attach,
        // so use the main-thread handler to keep overlay rain animations moving.
        handler.postDelayed(refreshRunnable,
                hasRainAnimation ? RAIN_UPDATE_INTERVAL : 100L);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startRefreshing();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRefreshing();
    }
}
