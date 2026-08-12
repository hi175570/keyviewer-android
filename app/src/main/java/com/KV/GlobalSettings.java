package com.KV;

import android.content.Context;
import android.content.SharedPreferences;

public class GlobalSettings {
    
    private static GlobalSettings instance;
    private Context context;
    private SharedPreferences prefs;
    
    // 悬浮窗设置
    public boolean floatWindowEnabled = false;
    public float floatWindowScale = 1.0f;
    public int floatWindowX = 100;
    public int floatWindowY = 200;
    
    // 键雨设置
    public int globalRainHeightDp = 200;
    public float globalRainWidthDp = 100;
    public float globalRainCornerRadiusDp = 4f;
    public int globalRainReserveHeightDp = 10;  // 新增：键雨预留高度
    
    // 深色模式
    public boolean darkModeEnabled = false;

    // Optional private image shown behind the editable KV canvas (never in the overlay).
    public String backgroundImageAsset = "";

    // Mechanical-switch chatter filter. Suppressed presses remain visible in KeyViewer.
    public boolean keyDebounceEnabled = false;
    public int keyDebounceMillis = 20;

    // Highest-priority filter: keys missing from KV, plus explicitly listed keys, are swallowed.
    public boolean keyRestrictionEnabled = false;
    public String keyRestrictionBlockedKeys = "";
    
    // 键盘校准
    public String keyboardDevicePath = "";
    public boolean keyboardCalibrated = false;
    
    private static final String PREFS_NAME = "kv_global_settings";
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.0f;
    
    private GlobalSettings(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }
    
    public static GlobalSettings getInstance(Context context) {
        if (instance == null) {
            instance = new GlobalSettings(context);
        }
        return instance;
    }
    
    private void load() {
        floatWindowEnabled = prefs.getBoolean("float_enabled", false);
        floatWindowScale = prefs.getFloat("float_scale", 1.0f);
        floatWindowX = prefs.getInt("float_x", 100);
        floatWindowY = prefs.getInt("float_y", 200);
        globalRainHeightDp = prefs.getInt("rain_height", 200);
        globalRainWidthDp = prefs.getFloat("rain_width", 100);
        globalRainCornerRadiusDp = prefs.getFloat("rain_radius", 4f);
        globalRainReserveHeightDp = prefs.getInt("rain_reserve_height", 10);
        darkModeEnabled = prefs.getBoolean("dark_mode", false);
        backgroundImageAsset = prefs.getString("background_image_asset", "");
        if (backgroundImageAsset == null
                || !backgroundImageAsset.matches("[A-Za-z0-9._-]*")) {
            backgroundImageAsset = "";
        }
        keyDebounceEnabled = prefs.getBoolean("key_debounce_enabled", false);
        keyDebounceMillis = prefs.getInt("key_debounce_millis", 20);
        keyRestrictionEnabled = prefs.getBoolean("key_restriction_enabled", false);
        keyRestrictionBlockedKeys = KeyRestrictionPolicy.normalizeBlockedKeys(
                prefs.getString("key_restriction_blocked_keys", ""));
        keyboardDevicePath = prefs.getString("keyboard_device_path", "");
        keyboardCalibrated = prefs.getBoolean("keyboard_calibrated", false);
        if (!Float.isFinite(floatWindowScale)) floatWindowScale = 1.0f;
        floatWindowScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, floatWindowScale));
        globalRainHeightDp = Math.max(0, Math.min(5000, globalRainHeightDp));
        globalRainReserveHeightDp = Math.max(0,
                Math.min(globalRainHeightDp, globalRainReserveHeightDp));
        if (!Float.isFinite(globalRainWidthDp)) globalRainWidthDp = 100f;
        globalRainWidthDp = Math.max(1f, Math.min(5000f, globalRainWidthDp));
        if (!Float.isFinite(globalRainCornerRadiusDp)) globalRainCornerRadiusDp = 4f;
        globalRainCornerRadiusDp = Math.max(0f, Math.min(1000f, globalRainCornerRadiusDp));
        keyDebounceMillis = Math.max(1, Math.min(200, keyDebounceMillis));
        if (keyboardDevicePath == null || !keyboardDevicePath.matches("/dev/input/event\\d+")) {
            keyboardDevicePath = "";
            keyboardCalibrated = false;
        }
    }
    
    public void save() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("float_enabled", floatWindowEnabled);
        editor.putFloat("float_scale", floatWindowScale);
        editor.putInt("float_x", floatWindowX);
        editor.putInt("float_y", floatWindowY);
        editor.putInt("rain_height", globalRainHeightDp);
        editor.putFloat("rain_width", globalRainWidthDp);
        editor.putFloat("rain_radius", globalRainCornerRadiusDp);
        editor.putInt("rain_reserve_height", globalRainReserveHeightDp);
        editor.putBoolean("dark_mode", darkModeEnabled);
        editor.putString("background_image_asset", backgroundImageAsset);
        editor.putBoolean("key_debounce_enabled", keyDebounceEnabled);
        editor.putInt("key_debounce_millis", keyDebounceMillis);
        keyRestrictionBlockedKeys = KeyRestrictionPolicy.normalizeBlockedKeys(
                keyRestrictionBlockedKeys);
        editor.putBoolean("key_restriction_enabled", keyRestrictionEnabled);
        editor.putString("key_restriction_blocked_keys", keyRestrictionBlockedKeys);
        editor.putString("keyboard_device_path", keyboardDevicePath);
        editor.putBoolean("keyboard_calibrated", keyboardCalibrated);
        editor.apply();
    }
    
    public void increaseScale() {
        floatWindowScale = Math.min(MAX_SCALE, floatWindowScale + 0.1f);
        save();
    }
    
    public void decreaseScale() {
        floatWindowScale = Math.max(MIN_SCALE, floatWindowScale - 0.1f);
        save();
    }
    
    public String getScalePercent() {
        return Math.round(floatWindowScale * 100) + "%";
    }
    
    public void resetFloatPosition() {
        floatWindowX = 100;
        floatWindowY = 200;
        save();
    }
    
    public void toggleDarkMode() {
        darkModeEnabled = !darkModeEnabled;
        save();
    }
}
