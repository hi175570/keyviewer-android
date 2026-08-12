package com.KV;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class KeyData {
    public String id;
    public float centerX;
    public float centerY;
    public float width;
    public float height;
    
    public String boundKey = "未绑定";
    public String displayLabel = "";
    
    public float cornerRadiusDp = 8f;
    public float borderWidthDp = 5f;
    
    // 放开时颜色
    public int fillColorUp = 0xFF999999;
    public int borderColorUp = 0xFF666666;
    public int textColorUp = 0xFFFFFFFF;
    public int countColorUp = 0xFFFFFFFF;
    
    // 按下时颜色
    public int fillColorDown = 0xFFFFFFFF;
    public int borderColorDown = 0xFF666666;
    public int textColorDown = 0xFFFFFFFF;
    public int countColorDown = 0xFFFFFFFF;
    
    // 旧字段兼容
    @Deprecated
    public int fillColor = 0xFF999999;
    @Deprecated
    public int borderColor = 0xFF666666;
    
    public float textSizeSp = 14f;
    public float countTextSizeSp = -1f;
    public boolean autoFitText = true;
    public boolean textShadowEnabled = false;
    public int textShadowColor = 0x80000000;
    public float textShadowOffsetXDp = 1f;
    public float textShadowOffsetYDp = 1f;
    public float textShadowBlurRadiusDp = 0f;
    public boolean showCount = true;
    public String fontAsset = "";
    public String imageAsset = "";
    public boolean imageReplacesLabel = true;
    
    public boolean mappingEnabled = false;
    public String mappedKey = "";
    /** Consume the physical key but keep its KeyViewer visual/count feedback. */
    public boolean ghostKey = false;
    
    public boolean rainEnabled = false;
    public float rainOffsetXDp = 0f;
    public float rainOffsetYDp = 0f;
    public float rainWidthPercent = 100f;
    public int rainColor = 0xFFFFFFFF;
    public float rainSpeedDp = 5f;
    public boolean rainGlowEnabled = false;
    public float rainGlowRadiusDp = 12f;
    
    public boolean kpsTotalNoWrap = false;
    public String counterLabelCase = "upper";
    public float textOffsetXDp = 0f;
    public float textOffsetYDp = 0f;
    public float countOffsetXDp = 0f;
    public float countOffsetYDp = 0f;
    
    public int zIndex = 0;
    
    public boolean isPressed = false;
    public boolean isKeyPressed = false;
    public long lastParticleTime = 0;
    public List<RainParticle> rainParticles = new ArrayList<>();
    
    public transient RectF rect = new RectF();
    
    public KeyData(String id, float centerX, float centerY, float width, float height) {
        this.id = id;
        this.centerX = centerX;
        this.centerY = centerY;
        this.width = width;
        this.height = height;
    }
    
    public boolean isSpecialKey() {
        if (displayLabel == null || displayLabel.isEmpty()) return false;
        String lower = displayLabel.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("kps") || lower.equals("total");
    }
    
    public String getDisplayText() {
        if (displayLabel != null && !displayLabel.isEmpty()) {
            return displayLabel;
        }
        return boundKey;
    }
    
    public void migrateFromOldFields() {
        if (fillColorUp == 0xFF999999 && fillColor != 0xFF999999) {
            fillColorUp = fillColor;
        }
        if (borderColorUp == 0xFF666666 && borderColor != 0xFF666666) {
            borderColorUp = borderColor;
            borderColorDown = borderColor;
        }
    }
    
    // ==================== 克隆方法（剪贴板用）====================
    
    public KeyData m4clone() {
        KeyData clone = new KeyData(UUID.randomUUID().toString(), 
            this.centerX, this.centerY, this.width, this.height);
        clone.boundKey = this.boundKey;
        clone.displayLabel = this.displayLabel;
        clone.cornerRadiusDp = this.cornerRadiusDp;
        clone.borderWidthDp = this.borderWidthDp;
        clone.fillColorUp = this.fillColorUp;
        clone.borderColorUp = this.borderColorUp;
        clone.textColorUp = this.textColorUp;
        clone.countColorUp = this.countColorUp;
        clone.fillColorDown = this.fillColorDown;
        clone.borderColorDown = this.borderColorDown;
        clone.textColorDown = this.textColorDown;
        clone.countColorDown = this.countColorDown;
        clone.textSizeSp = this.textSizeSp;
        clone.countTextSizeSp = this.countTextSizeSp;
        clone.autoFitText = this.autoFitText;
        clone.textShadowEnabled = this.textShadowEnabled;
        clone.textShadowColor = this.textShadowColor;
        clone.textShadowOffsetXDp = this.textShadowOffsetXDp;
        clone.textShadowOffsetYDp = this.textShadowOffsetYDp;
        clone.textShadowBlurRadiusDp = this.textShadowBlurRadiusDp;
        clone.showCount = this.showCount;
        clone.fontAsset = this.fontAsset;
        clone.imageAsset = this.imageAsset;
        clone.imageReplacesLabel = this.imageReplacesLabel;
        clone.mappingEnabled = this.mappingEnabled;
        clone.mappedKey = this.mappedKey;
        clone.ghostKey = this.ghostKey;
        clone.rainEnabled = this.rainEnabled;
        clone.rainOffsetXDp = this.rainOffsetXDp;
        clone.rainOffsetYDp = this.rainOffsetYDp;
        clone.rainWidthPercent = this.rainWidthPercent;
        clone.rainColor = this.rainColor;
        clone.rainSpeedDp = this.rainSpeedDp;
        clone.rainGlowEnabled = this.rainGlowEnabled;
        clone.rainGlowRadiusDp = this.rainGlowRadiusDp;
        clone.kpsTotalNoWrap = this.kpsTotalNoWrap;
        clone.counterLabelCase = this.counterLabelCase;
        clone.textOffsetXDp = this.textOffsetXDp;
        clone.textOffsetYDp = this.textOffsetYDp;
        clone.countOffsetXDp = this.countOffsetXDp;
        clone.countOffsetYDp = this.countOffsetYDp;
        clone.zIndex = this.zIndex;
        return clone;
    }
    
    public static class RainParticle {
        public float x;
        public float y;
        public float width;
        public float height;
        public int color;
        public float speed;
        public boolean isReleased;
        public boolean hasTouchedLine;
        public final float startY;
        
        public RainParticle(float x, float y, float width, int color, float speed) {
            this.x = x;
            this.y = y;
            this.startY = y;
            this.width = width;
            this.height = 0;
            this.color = color;
            this.speed = speed;
            this.isReleased = false;
            this.hasTouchedLine = false;
        }
    }
}
