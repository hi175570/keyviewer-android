package com.KV;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KeyManager {
    private static final String TAG = "KV_KeyManager";
    @SuppressLint("StaticFieldLeak") // Holds only Context#getApplicationContext().
    private static KeyManager sharedInstance;
    
    private List<KeyData> keys = new ArrayList<>();
    private List<KeyData> clipboard = new ArrayList<>();  // 剪贴板
    private Map<String, Integer> keyCodeCount = new HashMap<>();
    private String selectedKeyId = null;
    
    private int totalPressCount = 0;
    private LongRingBuffer recentPressTimes = new LongRingBuffer();
    
    private static final String PREFS_NAME = "keyviewer_prefs";
    private static final String KEY_TOTAL = "total_press_count";
    private static final String KEY_SELECTED = "selected_key_id";
    private static final int COUNT_SAVE_BATCH_SIZE = 50;
    private static final long COUNT_SAVE_INTERVAL_MS = 1000;
    
    private Context context;
    private int unsavedCountUpdates = 0;
    private long lastCountSaveElapsedMs = 0;
    
    private Runnable onDataChangedCallback;
    
    public void setOnDataChangedCallback(Runnable callback) {
        this.onDataChangedCallback = callback;
    }
    
    public void notifyDataChanged() {
        if (onDataChangedCallback != null) {
            onDataChangedCallback.run();
        }
    }
    
    public KeyManager() {
        this(null);
    }
    
    public KeyManager(Context context) {
        setContext(context);
    }

    public static synchronized KeyManager getInstance(Context context) {
        if (sharedInstance == null) {
            sharedInstance = new KeyManager(context);
            sharedInstance.loadFromPreferences();
        } else if (sharedInstance.context == null) {
            sharedInstance.setContext(context);
            sharedInstance.loadFromPreferences();
        }
        return sharedInstance;
    }
    
    public void setContext(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }
    
    // ==================== 剪贴板方法 ====================
    
    public List<KeyData> getClipboard() {
        return clipboard;
    }
    
    public void copyToClipboard(List<KeyData> source) {
        this.clipboard.clear();
        if (source != null) {
            for (KeyData key : source) {
                this.clipboard.add(key.m4clone());
            }
        }
        Log.d(TAG, "copyToClipboard: " + this.clipboard.size() + " keys");
    }
    
    public void pasteFromClipboard() {
        for (KeyData key : this.clipboard) {
            KeyData newKey = key.m4clone();
            this.keys.add(newKey);
        }
        saveToPreferences();
        notifyDataChanged();
        Log.d(TAG, "pasteFromClipboard: " + this.clipboard.size() + " keys");
    }
    
    // ==================== 按键 CRUD ====================
    
    public KeyData createNewKey(float centerX, float centerY, float width, float height) {
        String id = UUID.randomUUID().toString();
        KeyData key = new KeyData(id, centerX, centerY, width, height);
        
        int maxZ = getMaxZIndex();
        key.zIndex = maxZ + 1;
        keys.add(key);
        
        saveToPreferences();
        notifyDataChanged();
        Log.d(TAG, "createNewKey: " + id);
        return key;
    }
    
    public void deleteKey(String id) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).id.equals(id)) {
                keys.remove(i);
                break;
            }
        }
        if (id.equals(selectedKeyId)) {
            selectedKeyId = null;
        }
        saveToPreferences();
        notifyDataChanged();
        Log.d(TAG, "deleteKey: " + id);
    }

    public void deleteKeys(List<KeyData> keysToDelete) {
        if (keysToDelete == null || keysToDelete.isEmpty()) return;
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (KeyData key : keysToDelete) {
            if (key != null && key.id != null) ids.add(key.id);
        }
        keys.removeIf(key -> ids.contains(key.id));
        if (selectedKeyId != null && ids.contains(selectedKeyId)) selectedKeyId = null;
        saveToPreferences();
        notifyDataChanged();
    }

    public void addKeysToFront(List<KeyData> newKeys) {
        if (newKeys == null || newKeys.isEmpty()) return;
        int nextZ = getMaxZIndex() + 1;
        for (KeyData key : newKeys) {
            key.zIndex = nextZ++;
            keys.add(key);
        }
        saveToPreferences();
        notifyDataChanged();
    }
    
    public KeyData getSelectedKey() {
        if (selectedKeyId == null) return null;
        for (KeyData key : keys) {
            if (key.id.equals(selectedKeyId)) return key;
        }
        return null;
    }
    
    public void setSelectedKey(String id) {
        this.selectedKeyId = id;
        Log.d(TAG, "setSelectedKey: " + id);
    }
    
    public void clearSelection() {
        this.selectedKeyId = null;
        Log.d(TAG, "clearSelection");
    }
    
    public List<KeyData> getKeys() {
        return keys;
    }
    
    public List<KeyData> getKeysSortedByZIndex() {
        List<KeyData> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Integer.compare(b.zIndex, a.zIndex));
        return sorted;
    }
    
    public List<KeyData> getKeysSortedByZIndexAsc() {
        List<KeyData> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Integer.compare(a.zIndex, b.zIndex));
        return sorted;
    }
    
    public void bringToFront(String id) {
        KeyData target = null;
        for (KeyData key : keys) {
            if (key.id.equals(id)) {
                target = key;
                break;
            }
        }
        if (target == null) return;
        
        int maxZ = getMaxZIndex();
        if (target.zIndex == maxZ) return;
        
        target.zIndex = maxZ + 1;
        saveToPreferences();
        notifyDataChanged();
        Log.d(TAG, "bringToFront: " + id);
    }

    public void normalizeZIndexes() {
        if (keys.isEmpty()) return;
        List<KeyData> normalized = getKeysSortedByZIndex();
        for (int i = 0; i < normalized.size(); i++) {
            normalized.get(i).zIndex = normalized.size() - i;
        }
        saveToPreferences();
        notifyDataChanged();
    }

    private int getMaxZIndex() {
        int maxZIndex = 0;
        for (KeyData key : keys) {
            if (key.zIndex > maxZIndex) maxZIndex = key.zIndex;
        }
        return maxZIndex;
    }
    
    public KeyData findKeyAt(float worldX, float worldY) {
        List<KeyData> sortedKeys = getKeysSortedByZIndex();
        for (KeyData key : sortedKeys) {
            if (key.rect.contains(worldX, worldY)) return key;
        }
        return null;
    }
    
    // ==================== 按键计数 ====================
    
    public void recordKeyPress(String keyCode) {
        if (keyCode == null || keyCode.equals("未绑定") || keyCode.isEmpty()) return;
        
        Integer count = keyCodeCount.get(keyCode);
        int newCount = (count == null ? 1 : count + 1);
        keyCodeCount.put(keyCode, newCount);
        totalPressCount++;
        
        // KPS is an elapsed-time rate. Wall-clock time can jump when Android
        // synchronizes the clock, so always use the monotonic uptime clock.
        long now = SystemClock.elapsedRealtime();
        recentPressTimes.add(now);
        recentPressTimes.removeOlderThan(now - 1000);
        
        markCountStateDirtyAndMaybeSave();
        notifyDataChanged();
    }
    
    public int getKeyCount(String keyCode) {
        if (keyCode == null || keyCode.equals("未绑定") || keyCode.isEmpty()) return 0;
        Integer count = keyCodeCount.get(keyCode);
        return count == null ? 0 : count;
    }
    
    public int getTotalPressCount() {
        return totalPressCount;
    }
    
    public void setTotalPressCount(int total) {
        this.totalPressCount = total;
        notifyDataChanged();
    }
    
    public int getKPS() {
        long now = SystemClock.elapsedRealtime();
        recentPressTimes.removeOlderThan(now - 1000);
        return recentPressTimes.size();
    }
    
    public Map<String, Integer> getKeyCodeCount() {
        return keyCodeCount;
    }
    
    public void resetAllCounts() {
        keyCodeCount.clear();
        totalPressCount = 0;
        recentPressTimes.clear();
        unsavedCountUpdates = 0;
        saveCountsToPreferences();
        notifyDataChanged();
    }
    
    public float[] getKeysBounds() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (KeyData key : keys) {
            float halfW = key.width / 2f;
            float halfH = key.height / 2f;
            minX = Math.min(minX, key.centerX - halfW);
            minY = Math.min(minY, key.centerY - halfH);
            maxX = Math.max(maxX, key.centerX + halfW);
            maxY = Math.max(maxY, key.centerY + halfH);
        }
        return new float[]{minX, minY, maxX, maxY};
    }
    
    // ==================== 存档保存 ====================
    
    public void saveToPreferences() {
        if (context == null) return;
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putInt("key_count", keys.size());
        for (int i = 0; i < keys.size(); i++) {
            KeyData k = keys.get(i);
            String prefix = "key_" + i + "_";
            editor.putString(prefix + "id", k.id);
            editor.putFloat(prefix + "cx", k.centerX);
            editor.putFloat(prefix + "cy", k.centerY);
            editor.putFloat(prefix + "w", k.width);
            editor.putFloat(prefix + "h", k.height);
            editor.putString(prefix + "bound", k.boundKey);
            editor.putString(prefix + "label", k.displayLabel);
            editor.putFloat(prefix + "corner_radius", k.cornerRadiusDp);
            editor.putFloat(prefix + "border_width", k.borderWidthDp);
            editor.putInt(prefix + "fill_up", k.fillColorUp);
            editor.putInt(prefix + "border_up", k.borderColorUp);
            editor.putInt(prefix + "text_up", k.textColorUp);
            editor.putInt(prefix + "count_up", k.countColorUp);
            editor.putInt(prefix + "fill_down", k.fillColorDown);
            editor.putInt(prefix + "border_down", k.borderColorDown);
            editor.putInt(prefix + "text_down", k.textColorDown);
            editor.putInt(prefix + "count_down", k.countColorDown);
            editor.putFloat(prefix + "textSize", k.textSizeSp);
            editor.putFloat(prefix + "count_text_size", k.countTextSizeSp);
            editor.putBoolean(prefix + "auto_fit_text", k.autoFitText);
            editor.putBoolean(prefix + "text_shadow_enabled", k.textShadowEnabled);
            editor.putInt(prefix + "text_shadow_color", k.textShadowColor);
            editor.putFloat(prefix + "text_shadow_offset_x", k.textShadowOffsetXDp);
            editor.putFloat(prefix + "text_shadow_offset_y", k.textShadowOffsetYDp);
            editor.putFloat(prefix + "text_shadow_blur", k.textShadowBlurRadiusDp);
            editor.putBoolean(prefix + "showCount", k.showCount);
            editor.putString(prefix + "font_asset", k.fontAsset);
            editor.putString(prefix + "image_asset", k.imageAsset);
            editor.putBoolean(prefix + "image_replaces_label", k.imageReplacesLabel);
            editor.putBoolean(prefix + "mapping_en", k.mappingEnabled);
            editor.putString(prefix + "mapped", k.mappedKey);
            editor.putBoolean(prefix + "ghost_key", k.ghostKey);
            editor.putBoolean(prefix + "rain_en", k.rainEnabled);
            editor.putFloat(prefix + "rain_ox", k.rainOffsetXDp);
            editor.putFloat(prefix + "rain_oy", k.rainOffsetYDp);
            editor.putFloat(prefix + "rain_width", k.rainWidthPercent);
            editor.putInt(prefix + "rain_c", k.rainColor);
            editor.putFloat(prefix + "rain_sp", k.rainSpeedDp);
            editor.putBoolean(prefix + "rain_glow_en", k.rainGlowEnabled);
            editor.putFloat(prefix + "rain_glow_radius", k.rainGlowRadiusDp);
            editor.putBoolean(prefix + "no_wrap", k.kpsTotalNoWrap);
            editor.putString(prefix + "counter_label_case", k.counterLabelCase);
            editor.putFloat(prefix + "text_offset_x", k.textOffsetXDp);
            editor.putFloat(prefix + "text_offset_y", k.textOffsetYDp);
            editor.putFloat(prefix + "count_offset_x", k.countOffsetXDp);
            editor.putFloat(prefix + "count_offset_y", k.countOffsetYDp);
            editor.putInt(prefix + "zIndex", k.zIndex);
        }
        
        if (selectedKeyId != null) {
            editor.putString(KEY_SELECTED, selectedKeyId);
        } else {
            editor.remove(KEY_SELECTED);
        }
        editor.apply();
    }
    
    private void saveCountsToPreferences() {
        if (context == null) return;
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putInt("count_size", keyCodeCount.size());
        int idx = 0;
        for (Map.Entry<String, Integer> entry : keyCodeCount.entrySet()) {
            editor.putString("count_" + idx + "_k", entry.getKey());
            editor.putInt("count_" + idx + "_v", entry.getValue());
            idx++;
        }
        editor.putInt(KEY_TOTAL, totalPressCount);
        editor.apply();
        unsavedCountUpdates = 0;
        lastCountSaveElapsedMs = SystemClock.elapsedRealtime();
    }

    private void markCountStateDirtyAndMaybeSave() {
        unsavedCountUpdates++;
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean shouldSaveByBatch = unsavedCountUpdates >= COUNT_SAVE_BATCH_SIZE;
        boolean shouldSaveByTime = lastCountSaveElapsedMs == 0 ||
                nowElapsed - lastCountSaveElapsedMs >= COUNT_SAVE_INTERVAL_MS;
        if (shouldSaveByBatch || shouldSaveByTime) {
            saveCountsToPreferences();
        }
    }

    public void flushPendingCountSaves() {
        if (unsavedCountUpdates > 0) saveCountsToPreferences();
    }
    
    // ==================== 存档加载 ====================
    
    public void loadFromPreferences() {
        if (context == null) return;
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        int keyCount = prefs.getInt("key_count", 0);
        keys.clear();
        for (int i = 0; i < keyCount; i++) {
            String prefix = "key_" + i + "_";
            KeyData key = loadKeyFromPrefs(prefs, prefix);
            keys.add(key);
        }
        
        selectedKeyId = prefs.getString(KEY_SELECTED, null);
        loadCountsFromPrefs(prefs);
        Log.d(TAG, "loadFromPreferences: " + keys.size() + " keys");
    }
    
    private KeyData loadKeyFromPrefs(SharedPreferences prefs, String prefix) {
        String id = prefs.getString(prefix + "id", UUID.randomUUID().toString());
        float cx = prefs.getFloat(prefix + "cx", 100);
        float cy = prefs.getFloat(prefix + "cy", 100);
        float w = prefs.getFloat(prefix + "w", 100);
        float h = prefs.getFloat(prefix + "h", 100);
        
        KeyData key = new KeyData(id, cx, cy, w, h);
        
        int oldFillColor = prefs.getInt(prefix + "fill", 0xFF999999);
        int oldBorderColor = prefs.getInt(prefix + "border", 0xFF666666);
        
        key.boundKey = prefs.getString(prefix + "bound", "未绑定");
        key.displayLabel = prefs.getString(prefix + "label", "");
        key.cornerRadiusDp = prefs.getFloat(prefix + "corner_radius", 8f);
        key.borderWidthDp = prefs.getFloat(prefix + "border_width", 5f);
        key.fillColorUp = prefs.getInt(prefix + "fill_up", oldFillColor);
        key.borderColorUp = prefs.getInt(prefix + "border_up", oldBorderColor);
        key.textColorUp = prefs.getInt(prefix + "text_up", 0xFFFFFFFF);
        key.countColorUp = prefs.getInt(prefix + "count_up", key.textColorUp);
        key.fillColorDown = prefs.getInt(prefix + "fill_down", 0xFFFFFFFF);
        key.borderColorDown = prefs.getInt(prefix + "border_down", key.borderColorUp);
        key.textColorDown = prefs.getInt(prefix + "text_down", key.textColorUp);
        key.countColorDown = prefs.getInt(prefix + "count_down", key.countColorUp);
        key.textSizeSp = prefs.getFloat(prefix + "textSize", 14f);
        key.countTextSizeSp = prefs.getFloat(prefix + "count_text_size", -1f);
        key.autoFitText = prefs.getBoolean(prefix + "auto_fit_text", true);
        key.textShadowEnabled = prefs.getBoolean(prefix + "text_shadow_enabled", false);
        key.textShadowColor = prefs.getInt(prefix + "text_shadow_color", 0x80000000);
        key.textShadowOffsetXDp = prefs.getFloat(prefix + "text_shadow_offset_x", 1f);
        key.textShadowOffsetYDp = prefs.getFloat(prefix + "text_shadow_offset_y", 1f);
        key.textShadowBlurRadiusDp = prefs.getFloat(prefix + "text_shadow_blur", 0f);
        key.showCount = prefs.getBoolean(prefix + "showCount", true);
        key.fontAsset = prefs.getString(prefix + "font_asset", "");
        key.imageAsset = prefs.getString(prefix + "image_asset", "");
        key.imageReplacesLabel = prefs.getBoolean(prefix + "image_replaces_label", true);
        key.mappingEnabled = prefs.getBoolean(prefix + "mapping_en", false);
        key.mappedKey = prefs.getString(prefix + "mapped", "");
        key.ghostKey = prefs.getBoolean(prefix + "ghost_key", false);
        key.rainEnabled = prefs.getBoolean(prefix + "rain_en", false);
        key.rainOffsetXDp = prefs.getFloat(prefix + "rain_ox", 0f);
        key.rainOffsetYDp = prefs.getFloat(prefix + "rain_oy", 0f);
        key.rainWidthPercent = prefs.getFloat(prefix + "rain_width", 100f);
        key.rainColor = prefs.getInt(prefix + "rain_c", 0xFFFFFFFF);
        key.rainSpeedDp = prefs.getFloat(prefix + "rain_sp", 5f);
        key.rainGlowEnabled = prefs.getBoolean(prefix + "rain_glow_en", false);
        key.rainGlowRadiusDp = prefs.getFloat(prefix + "rain_glow_radius", 12f);
        key.kpsTotalNoWrap = prefs.getBoolean(prefix + "no_wrap", false);
        key.counterLabelCase = prefs.getString(prefix + "counter_label_case", "upper");
        key.textOffsetXDp = prefs.contains(prefix + "text_offset_x")
                ? prefs.getFloat(prefix + "text_offset_x", 0f) : prefs.getFloat(prefix + "label_offset_x", 0f);
        key.textOffsetYDp = prefs.contains(prefix + "text_offset_y")
                ? prefs.getFloat(prefix + "text_offset_y", 0f) : prefs.getFloat(prefix + "label_offset_y", 0f);
        key.countOffsetXDp = prefs.contains(prefix + "count_offset_x")
                ? prefs.getFloat(prefix + "count_offset_x", 0f) : prefs.getFloat(prefix + "value_offset_x", 0f);
        key.countOffsetYDp = prefs.contains(prefix + "count_offset_y")
                ? prefs.getFloat(prefix + "count_offset_y", 0f) : prefs.getFloat(prefix + "value_offset_y", 0f);
        key.zIndex = prefs.getInt(prefix + "zIndex", 0);
        
        return key;
    }
    
    private void loadCountsFromPrefs(SharedPreferences prefs) {
        int countSize = prefs.getInt("count_size", 0);
        keyCodeCount.clear();
        for (int i = 0; i < countSize; i++) {
            String k = prefs.getString("count_" + i + "_k", "");
            int v = prefs.getInt("count_" + i + "_v", 0);
            if (!k.isEmpty()) keyCodeCount.put(k, v);
        }
        totalPressCount = prefs.getInt(KEY_TOTAL, 0);
        recentPressTimes.clear();
        unsavedCountUpdates = 0;
        lastCountSaveElapsedMs = SystemClock.elapsedRealtime();
    }
    
    public JSONObject exportToJson() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray keysArray = new JSONArray();
        for (KeyData key : keys) {
            JSONObject keyJson = new JSONObject();
            keyJson.put("id", key.id);
            keyJson.put("centerX", key.centerX);
            keyJson.put("centerY", key.centerY);
            keyJson.put("width", key.width);
            keyJson.put("height", key.height);
            keyJson.put("boundKey", key.boundKey);
            keyJson.put("displayLabel", key.displayLabel);
            keyJson.put("fillColorUp", key.fillColorUp);
            keyJson.put("borderColorUp", key.borderColorUp);
            keyJson.put("textColorUp", key.textColorUp);
            keyJson.put("countColorUp", key.countColorUp);
            keyJson.put("fillColorDown", key.fillColorDown);
            keyJson.put("borderColorDown", key.borderColorDown);
            keyJson.put("textColorDown", key.textColorDown);
            keyJson.put("countColorDown", key.countColorDown);
            keyJson.put("textSizeSp", key.textSizeSp);
            if (key.countTextSizeSp > 0f) keyJson.put("countTextSizeSp", key.countTextSizeSp);
            keyJson.put("autoFitText", key.autoFitText);
            keyJson.put("textShadowEnabled", key.textShadowEnabled);
            keyJson.put("textShadowColor", key.textShadowColor);
            keyJson.put("textShadowOffsetXDp", key.textShadowOffsetXDp);
            keyJson.put("textShadowOffsetYDp", key.textShadowOffsetYDp);
            keyJson.put("textShadowBlurRadiusDp", key.textShadowBlurRadiusDp);
            keyJson.put("showCount", key.showCount);
            keyJson.put("fontAsset", key.fontAsset);
            keyJson.put("imageAsset", key.imageAsset);
            keyJson.put("imageReplacesLabel", key.imageReplacesLabel);
            keyJson.put("mappingEnabled", key.mappingEnabled);
            keyJson.put("mappedKey", key.mappedKey);
            keyJson.put("ghostKey", key.ghostKey);
            keyJson.put("rainEnabled", key.rainEnabled);
            keyJson.put("rainOffsetXDp", key.rainOffsetXDp);
            keyJson.put("rainOffsetYDp", key.rainOffsetYDp);
            keyJson.put("rainWidthPercent", key.rainWidthPercent);
            keyJson.put("rainColor", key.rainColor);
            keyJson.put("rainSpeedDp", key.rainSpeedDp);
            keyJson.put("rainGlowEnabled", key.rainGlowEnabled);
            keyJson.put("rainGlowRadiusDp", key.rainGlowRadiusDp);
            keyJson.put("kpsTotalNoWrap", key.kpsTotalNoWrap);
            keyJson.put("counterLabelCase", key.counterLabelCase);
            keyJson.put("textOffsetXDp", key.textOffsetXDp);
            keyJson.put("textOffsetYDp", key.textOffsetYDp);
            keyJson.put("countOffsetXDp", key.countOffsetXDp);
            keyJson.put("countOffsetYDp", key.countOffsetYDp);
            keyJson.put("zIndex", key.zIndex);
            keysArray.put(keyJson);
        }
        root.put("keys", keysArray);
        
        JSONObject countsJson = new JSONObject();
        for (Map.Entry<String, Integer> entry : keyCodeCount.entrySet()) {
            countsJson.put(entry.getKey(), entry.getValue());
        }
        root.put("keyCounts", countsJson);
        root.put("totalPressCount", totalPressCount);
        return root;
    }
    
    public void updateKeyAndSave(KeyData key) {
        saveToPreferences();
        notifyDataChanged();
    }

    public void replaceAllData(List<KeyData> newKeys, Map<String, Integer> newCounts,
                               int newTotalPressCount) {
        keys.clear();
        if (newKeys != null) keys.addAll(newKeys);
        keyCodeCount.clear();
        if (newCounts != null) keyCodeCount.putAll(newCounts);
        totalPressCount = Math.max(0, newTotalPressCount);
        selectedKeyId = null;
        recentPressTimes.clear();
        unsavedCountUpdates = 0;
        saveToPreferences();
        saveCountsToPreferences();
        notifyDataChanged();
    }

    private static class LongRingBuffer {
        private long[] values = new long[64];
        private int head = 0;
        private int size = 0;

        void add(long value) {
            ensureCapacity(size + 1);
            int tail = (head + size) % values.length;
            values[tail] = value;
            size++;
        }

        void removeOlderThan(long minInclusiveTimestamp) {
            while (size > 0 && values[head] < minInclusiveTimestamp) {
                head = (head + 1) % values.length;
                size--;
            }
        }

        int size() { return size; }

        void clear() {
            head = 0;
            size = 0;
        }

        private void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity <= values.length) return;
            int newCapacity = values.length * 2;
            while (newCapacity < requiredCapacity) newCapacity *= 2;
            long[] newValues = new long[newCapacity];
            for (int i = 0; i < size; i++) {
                newValues[i] = values[(head + i) % values.length];
            }
            values = newValues;
            head = 0;
        }
    }
}
