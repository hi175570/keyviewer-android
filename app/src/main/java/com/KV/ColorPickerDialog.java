package com.KV;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Point;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ColorPickerDialog extends Dialog {

    private OnColorSelectedListener listener;
    private int currentColor = 0xFFFFFFFF;
    
    private ColorPickerView colorPickerView;
    private HueSliderView hueSliderView;
    private AlphaSliderView alphaSliderView;
    private View colorPreview;
    private TextView hexText;
    private Button confirmBtn;
    private Button cancelBtn;
    
    private float currentHue = 0f;
    private float currentSaturation = 1f;
    private float currentValue = 1f;
    private int currentAlpha = 255;
    
    private View anchorView;
    
    public interface OnColorSelectedListener {
        void onColorSelected(String hexColor);
    }
    
    interface OnColorChangeListener {
        void onColorChange(float saturation, float value);
    }
    
    interface OnHueChangeListener {
        void onHueChange(float hue);
    }
    
    interface OnAlphaChangeListener {
        void onAlphaChange(int alpha);
    }
    
    public ColorPickerDialog(Context context, View anchorView) {
        super(context);
        this.anchorView = anchorView;
        init();
    }
    
    public ColorPickerDialog(Context context, int initialColor, View anchorView) {
        super(context);
        this.currentColor = initialColor;
        this.anchorView = anchorView;
        init();
    }
    
    private void init() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(createContentView());
        
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            
            Point displaySize = new Point();
            window.getWindowManager().getDefaultDisplay().getSize(displaySize);
            int screenWidth = displaySize.x;
            
            // 大小改为原来的 0.8 倍
            int size = (int)(screenWidth * 0.4);
            params.width = size;
            params.height = size;
            
            params.dimAmount = 0.3f;
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            
            if (anchorView != null) {
                int[] location = new int[2];
                anchorView.getLocationOnScreen(location);
                params.x = location[0] - size + anchorView.getWidth();
                params.y = location[1] - size / 2;
                
                if (params.x < 0) params.x = 16;
                if (params.y < 0) params.y = 16;
                if (params.y + size > displaySize.y) {
                    params.y = displaySize.y - size - 16;
                }
            } else {
                params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            }
            
            window.setAttributes(params);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        parseColor(currentColor);
    }
    
    private View createContentView() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(12, 12, 12, 12);
        layout.setBackgroundColor(0xFFF5F5F5);
        
        TextView title = new TextView(getContext());
        title.setText("选择颜色");
        title.setTextSize(14);
        title.setTextColor(0xFF333333);
        title.setPadding(0, 0, 0, 8);
        layout.addView(title);
        
        colorPreview = new View(getContext());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 40);
        previewParams.bottomMargin = 8;
        colorPreview.setLayoutParams(previewParams);
        colorPreview.setBackgroundColor(currentColor);
        layout.addView(colorPreview);
        
        colorPickerView = new ColorPickerView(getContext());
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        pickerParams.bottomMargin = 8;
        colorPickerView.setLayoutParams(pickerParams);
        layout.addView(colorPickerView);
        
        hueSliderView = new HueSliderView(getContext());
        LinearLayout.LayoutParams hueParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 24);
        hueParams.bottomMargin = 8;
        hueSliderView.setLayoutParams(hueParams);
        layout.addView(hueSliderView);
        
        alphaSliderView = new AlphaSliderView(getContext());
        LinearLayout.LayoutParams alphaParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 24);
        alphaParams.bottomMargin = 12;
        alphaSliderView.setLayoutParams(alphaParams);
        layout.addView(alphaSliderView);
        
        LinearLayout bottomRow = new LinearLayout(getContext());
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        hexText = new TextView(getContext());
        hexText.setText(ColorUtils.intToHex(currentColor));
        hexText.setTextSize(11);
        hexText.setTextColor(0xFF666666);
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        hexText.setLayoutParams(hexParams);
        bottomRow.addView(hexText);
        
        LinearLayout buttonLayout = new LinearLayout(getContext());
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        confirmBtn = new Button(getContext());
        confirmBtn.setText("确定");
        confirmBtn.setTextSize(11);
        confirmBtn.setTextColor(0xFFFFFFFF);
        confirmBtn.setBackgroundColor(0xFF2196F3);
        confirmBtn.setPadding(16, 6, 16, 6);
        confirmBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onColorSelected(ColorUtils.intToHex(currentColor));
            }
            dismiss();
        });
        buttonLayout.addView(confirmBtn);
        
        cancelBtn = new Button(getContext());
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(11);
        cancelBtn.setTextColor(0xFF666666);
        cancelBtn.setBackgroundColor(0xFFE0E0E0);
        cancelBtn.setPadding(16, 6, 16, 6);
        cancelBtn.setOnClickListener(v -> dismiss());
        buttonLayout.addView(cancelBtn);
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.leftMargin = 8;
        buttonLayout.setLayoutParams(buttonParams);
        bottomRow.addView(buttonLayout);
        
        layout.addView(bottomRow);
        
        colorPickerView.setOnColorChangeListener((saturation, value) -> {
            currentSaturation = saturation;
            currentValue = value;
            updateColor();
        });
        
        hueSliderView.setOnHueChangeListener(hue -> {
            currentHue = hue;
            colorPickerView.updateHue(hue);
            alphaSliderView.updateBaseColor(ColorUtils.hsvToRgb(currentHue, 1f, 1f));
            updateColor();
        });
        
        alphaSliderView.setOnAlphaChangeListener(alpha -> {
            currentAlpha = alpha;
            updateColor();
        });
        
        return layout;
    }
    
    private void parseColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        currentHue = hsv[0];
        currentSaturation = hsv[1];
        currentValue = hsv[2];
        currentAlpha = Color.alpha(color);
        
        if (colorPickerView != null) {
            colorPickerView.updateHue(currentHue);
            colorPickerView.setPosition(currentSaturation, currentValue);
        }
        if (hueSliderView != null) {
            hueSliderView.setHue(currentHue);
        }
        if (alphaSliderView != null) {
            int rgbColor = ColorUtils.hsvToRgb(currentHue, currentSaturation, currentValue);
            alphaSliderView.updateBaseColor(rgbColor);
            alphaSliderView.setAlpha(currentAlpha);
        }
    }
    
    private void updateColor() {
        int rgbColor = ColorUtils.hsvToRgb(currentHue, currentSaturation, currentValue);
        currentColor = (currentAlpha << 24) | (rgbColor & 0x00FFFFFF);
        colorPreview.setBackgroundColor(currentColor);
        hexText.setText(ColorUtils.intToHex(currentColor));
        alphaSliderView.updateBaseColor(rgbColor);
    }
    
    public void setOnColorSelectedListener(OnColorSelectedListener listener) {
        this.listener = listener;
    }
    
    // ==================== 饱和度/亮度选择器 ====================
    
    private class ColorPickerView extends View {
        
        private final Paint crosshairPaint;
        private final Paint saturationPaint;
        private final Paint valuePaint;
        private final PorterDuffXfermode multiplyMode;
        private float currentSaturation = 1f;
        private float currentValue = 1f;
        private float currentHue = 0f;
        private int panelWidth;
        private int panelHeight;
        private OnColorChangeListener listener;
        
        public ColorPickerView(Context context) {
            super(context);
            crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            crosshairPaint.setColor(0xFFFFFFFF);
            crosshairPaint.setStrokeWidth(2);
            crosshairPaint.setStyle(Paint.Style.STROKE);
            saturationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            multiplyMode = new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY);
            valuePaint.setXfermode(multiplyMode);
        }
        
        public void updateHue(float hue) {
            currentHue = hue;
            rebuildGradients();
            invalidate();
        }
        
        public void setPosition(float saturation, float value) {
            currentSaturation = saturation;
            currentValue = value;
            invalidate();
        }
        
        public void setOnColorChangeListener(OnColorChangeListener listener) {
            this.listener = listener;
        }
        
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            panelWidth = w;
            panelHeight = h;
            rebuildGradients();
        }

        private void rebuildGradients() {
            if (panelWidth <= 0 || panelHeight <= 0) return;
            int pureColor = ColorUtils.hsvToRgb(currentHue, 1f, 1f);
            saturationPaint.setShader(new LinearGradient(
                    0, 0, panelWidth, 0,
                    0xFFFFFFFF, pureColor, Shader.TileMode.CLAMP));
            valuePaint.setShader(new LinearGradient(
                    0, 0, 0, panelHeight,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, panelWidth, panelHeight, saturationPaint);
            canvas.drawRect(0, 0, panelWidth, panelHeight, valuePaint);
            
            float x = currentSaturation * panelWidth;
            float y = (1f - currentValue) * panelHeight;
            canvas.drawCircle(x, y, 8, crosshairPaint);
            canvas.drawCircle(x, y, 5, crosshairPaint);
        }
        
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) return true;
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP) return false;
            if (panelWidth <= 0 || panelHeight <= 0) return false;
            float x = event.getX();
            float y = event.getY();
            
            x = Math.max(0, Math.min(panelWidth, x));
            y = Math.max(0, Math.min(panelHeight, y));
            
            currentSaturation = x / panelWidth;
            currentValue = 1f - (y / panelHeight);
            
            invalidate();
            
            if (listener != null) {
                listener.onColorChange(currentSaturation, currentValue);
            }
            if (action == MotionEvent.ACTION_UP) performClick();
            
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
    
    // ==================== 色相选择条 ====================
    
    private class HueSliderView extends View {
        
        private final Paint paint;
        private final Paint thumbPaint;
        private final int[] hueColors = new int[361];
        private final RectF trackRect = new RectF();
        private float currentHue = 0f;
        private int viewWidth;
        private int viewHeight;
        private OnHueChangeListener listener;
        
        public HueSliderView(Context context) {
            super(context);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            thumbPaint.setColor(0xFFFFFFFF);
            thumbPaint.setStyle(Paint.Style.STROKE);
            thumbPaint.setStrokeWidth(2);
            for (int i = 0; i <= 360; i++) {
                hueColors[i] = ColorUtils.hsvToRgb(i, 1f, 1f);
            }
        }
        
        public void setHue(float hue) {
            currentHue = hue;
            invalidate();
        }
        
        public void setOnHueChangeListener(OnHueChangeListener listener) {
            this.listener = listener;
        }
        
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            viewWidth = w;
            viewHeight = h;
            trackRect.set(0, 3, viewWidth, viewHeight - 3);
            paint.setShader(new LinearGradient(
                    0, 0, viewWidth, 0,
                    hueColors, null, Shader.TileMode.CLAMP));
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRoundRect(trackRect, 4, 4, paint);
            
            float thumbX = (currentHue / 360f) * viewWidth;
            canvas.drawCircle(thumbX, viewHeight / 2f, 8, thumbPaint);
            canvas.drawCircle(thumbX, viewHeight / 2f, 5, thumbPaint);
        }
        
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) return true;
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP) return false;
            if (viewWidth <= 0) return false;
            float x = event.getX();
            x = Math.max(0, Math.min(viewWidth, x));
            currentHue = (x / viewWidth) * 360f;
            invalidate();
            
            if (listener != null) {
                listener.onHueChange(currentHue);
            }
            if (action == MotionEvent.ACTION_UP) performClick();
            
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
    
    // ==================== 透明度选择条 ====================
    
    private class AlphaSliderView extends View {
        
        private final Paint bgPaint;
        private final Paint gradientPaint;
        private final Paint thumbPaint;
        private final RectF trackRect = new RectF();
        private int currentAlpha = 255;
        private int baseColor = 0xFF000000;
        private int viewWidth;
        private int viewHeight;
        private OnAlphaChangeListener listener;
        
        public AlphaSliderView(Context context) {
            super(context);
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            thumbPaint.setColor(0xFFFFFFFF);
            thumbPaint.setStyle(Paint.Style.STROKE);
            thumbPaint.setStrokeWidth(2);
        }
        
        public void updateBaseColor(int color) {
            baseColor = color;
            rebuildGradient();
            invalidate();
        }
        
        public void setAlpha(int alpha) {
            currentAlpha = alpha;
            invalidate();
        }
        
        public void setOnAlphaChangeListener(OnAlphaChangeListener listener) {
            this.listener = listener;
        }
        
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            viewWidth = w;
            viewHeight = h;
            trackRect.set(0, 3, viewWidth, viewHeight - 3);
            rebuildGradient();
        }

        private void rebuildGradient() {
            if (viewWidth <= 0) return;
            int transparentColor = baseColor & 0x00FFFFFF;
            int opaqueColor = baseColor | 0xFF000000;
            gradientPaint.setShader(new LinearGradient(
                    0, 0, viewWidth, 0,
                    transparentColor, opaqueColor, Shader.TileMode.CLAMP));
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            int cellSize = 6;
            int startX = 0;
            int startY = 0;
            int endX = viewWidth;
            int endY = viewHeight;
            
            bgPaint.setColor(0xFFCCCCCC);
            canvas.drawRect(startX, startY, endX, endY, bgPaint);
            
            bgPaint.setColor(0xFFFFFFFF);
            for (int y = startY; y < endY; y += cellSize) {
                for (int x = startX + ((y / cellSize) % 2) * cellSize; x < endX; x += cellSize * 2) {
                    canvas.drawRect(x, y, x + cellSize, y + cellSize, bgPaint);
                }
            }
            
            canvas.drawRoundRect(trackRect, 4, 4, gradientPaint);
            
            float thumbX = (currentAlpha / 255f) * viewWidth;
            canvas.drawCircle(thumbX, viewHeight / 2f, 8, thumbPaint);
            canvas.drawCircle(thumbX, viewHeight / 2f, 5, thumbPaint);
        }
        
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) return true;
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP) return false;
            if (viewWidth <= 0) return false;
            float x = event.getX();
            x = Math.max(0, Math.min(viewWidth, x));
            currentAlpha = (int) ((x / viewWidth) * 255);
            invalidate();
            
            if (listener != null) {
                listener.onAlphaChange(currentAlpha);
            }
            if (action == MotionEvent.ACTION_UP) performClick();
            
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
