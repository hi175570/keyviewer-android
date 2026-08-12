package com.KV;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ConfigCodec {

    static final int MAX_KEYS = 5000;
    private static final int MAX_COUNT_ENTRIES = 10000;
    private static final float GRID_SIZE_DP = 15f;

    private ConfigCodec() {}

    static JSONObject encode(KeyManager manager, float density)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("formatVersion", 4);

        JSONArray keys = new JSONArray();
        for (KeyData key : new ArrayList<>(manager.getKeys())) {
            JSONObject json = new JSONObject();
            json.put("id", key.id);
            json.put("centerX", key.centerX / density);
            json.put("centerY", key.centerY / density);
            json.put("width", key.width / density);
            json.put("height", key.height / density);
            json.put("boundKey", key.boundKey);
            json.put("displayLabel", key.displayLabel);
            json.put("cornerRadiusDp", key.cornerRadiusDp);
            json.put("borderWidthDp", key.borderWidthDp);
            json.put("fillColorUp", key.fillColorUp);
            json.put("borderColorUp", key.borderColorUp);
            json.put("textColorUp", key.textColorUp);
            json.put("countColorUp", key.countColorUp);
            json.put("fillColorDown", key.fillColorDown);
            json.put("borderColorDown", key.borderColorDown);
            json.put("textColorDown", key.textColorDown);
            json.put("countColorDown", key.countColorDown);
            json.put("textSizeSp", key.textSizeSp);
            if (key.countTextSizeSp > 0f) json.put("countTextSizeSp", key.countTextSizeSp);
            json.put("autoFitText", key.autoFitText);
            json.put("textShadowEnabled", key.textShadowEnabled);
            json.put("textShadowColor", key.textShadowColor);
            json.put("textShadowOffsetXDp", key.textShadowOffsetXDp);
            json.put("textShadowOffsetYDp", key.textShadowOffsetYDp);
            json.put("textShadowBlurRadiusDp", key.textShadowBlurRadiusDp);
            json.put("showCount", key.showCount);
            json.put("fontAsset", key.fontAsset);
            json.put("imageAsset", key.imageAsset);
            json.put("imageReplacesLabel", key.imageReplacesLabel);
            json.put("mappingEnabled", key.mappingEnabled);
            json.put("mappedKey", key.mappedKey);
            json.put("ghostKey", key.ghostKey);
            json.put("rainEnabled", key.rainEnabled);
            json.put("rainOffsetXDp", key.rainOffsetXDp);
            json.put("rainOffsetYDp", key.rainOffsetYDp);
            json.put("rainWidthPercent", key.rainWidthPercent);
            json.put("rainColor", key.rainColor);
            json.put("rainSpeedDp", key.rainSpeedDp);
            json.put("rainGlowEnabled", key.rainGlowEnabled);
            json.put("rainGlowRadiusDp", key.rainGlowRadiusDp);
            json.put("kpsTotalNoWrap", key.kpsTotalNoWrap);
            json.put("counterLabelCase", key.counterLabelCase);
            json.put("textOffsetXDp", key.textOffsetXDp);
            json.put("textOffsetYDp", key.textOffsetYDp);
            json.put("countOffsetXDp", key.countOffsetXDp);
            json.put("countOffsetYDp", key.countOffsetYDp);
            json.put("zIndex", key.zIndex);
            keys.put(json);
        }
        root.put("keys", keys);

        JSONObject counts = new JSONObject();
        for (Map.Entry<String, Integer> entry : new HashMap<>(manager.getKeyCodeCount()).entrySet()) {
            counts.put(entry.getKey(), Math.max(0, entry.getValue()));
        }
        root.put("keyCounts", counts);
        root.put("totalPressCount", Math.max(0, manager.getTotalPressCount()));
        return root;
    }

    static GridAlignmentResult apply(JSONObject root, KeyManager manager, float density)
            throws JSONException {
        JSONArray keyArray = root.optJSONArray("keys");
        if (keyArray == null) {
            throw new JSONException("Missing keys array");
        }
        if (keyArray.length() > MAX_KEYS) {
            throw new JSONException("Too many keys (maximum " + MAX_KEYS + ")");
        }

        float safeDensity = Float.isFinite(density) && density > 0f ? density : 1f;
        List<KeyData> parsedKeys = new ArrayList<>(keyArray.length());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < keyArray.length(); i++) {
            JSONObject json = keyArray.optJSONObject(i);
            if (json == null) {
                throw new JSONException("Invalid key at index " + i);
            }
            parsedKeys.add(decodeKey(json, safeDensity, ids));
        }
        GridAlignmentResult gridAlignment = alignKeysToGrid(parsedKeys, safeDensity);

        Map<String, Integer> counts = new HashMap<>();
        JSONObject countJson = root.optJSONObject("keyCounts");
        long countSum = 0;
        if (countJson != null) {
            Iterator<String> names = countJson.keys();
            while (names.hasNext()) {
                if (counts.size() >= MAX_COUNT_ENTRIES) {
                    throw new JSONException("Too many count entries");
                }
                String originalName = names.next();
                String name = limited(originalName, 128);
                int count = Math.max(0, countJson.optInt(originalName, 0));
                counts.put(name, count);
                countSum = Math.min(Integer.MAX_VALUE, countSum + count);
            }
        }
        int total = root.has("totalPressCount")
                ? Math.max(0, root.optInt("totalPressCount", 0))
                : (int) countSum;

        // Global settings belong to the application, not to an individual KV layout.
        // Legacy files may still contain globalSettings; deliberately ignore it.
        manager.replaceAllData(parsedKeys, counts, total);
        return gridAlignment;
    }

    private static GridAlignmentResult alignKeysToGrid(List<KeyData> keys, float density) {
        if (keys.isEmpty()) {
            return GridAlignmentResult.NONE;
        }

        float gridSize = GRID_SIZE_DP * density;
        float tolerance = Math.max(0.001f, density * 0.001f);
        float[][] centers = new float[keys.size()][2];
        for (int index = 0; index < keys.size(); index++) {
            centers[index][0] = keys.get(index).centerX;
            centers[index][1] = keys.get(index).centerY;
        }
        LayoutGridAligner.Result result = LayoutGridAligner.align(centers, gridSize, tolerance);
        for (int index = 0; index < keys.size(); index++) {
            keys.get(index).centerX = centers[index][0];
            keys.get(index).centerY = centers[index][1];
        }
        return new GridAlignmentResult(
                result.offsetX,
                result.offsetY,
                result.groupMovedItemCount,
                result.individuallyAdjustedItemCount);
    }

    static final class GridAlignmentResult {
        static final GridAlignmentResult NONE = new GridAlignmentResult(0f, 0f, 0, 0);

        final float offsetX;
        final float offsetY;
        final int groupMovedKeyCount;
        final int individuallyAdjustedKeyCount;

        GridAlignmentResult(
                float offsetX,
                float offsetY,
                int groupMovedKeyCount,
                int individuallyAdjustedKeyCount) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.groupMovedKeyCount = groupMovedKeyCount;
            this.individuallyAdjustedKeyCount = individuallyAdjustedKeyCount;
        }

        boolean changed() {
            return groupMovedKeyCount > 0 || individuallyAdjustedKeyCount > 0;
        }
    }

    private static KeyData decodeKey(JSONObject json, float density, Set<String> ids) {
        String id = limited(json.optString("id", ""), 128);
        if (id.isEmpty() || !ids.add(id)) {
            do {
                id = UUID.randomUUID().toString();
            } while (!ids.add(id));
        }

        float centerX = finite(json.optDouble("centerX", 100), -100000f, 100000f, 100f) * density;
        float centerY = finite(json.optDouble("centerY", 100), -100000f, 100000f, 100f) * density;
        float width = finite(json.optDouble("width", 50), 8f, 5000f, 50f) * density;
        float height = finite(json.optDouble("height", 50), 8f, 5000f, 50f) * density;
        KeyData key = new KeyData(id, centerX, centerY, width, height);
        key.boundKey = limited(json.optString("boundKey", "未绑定"), 128);
        key.displayLabel = limited(json.optString("displayLabel", ""), 256);
        key.cornerRadiusDp = finite(json.optDouble("cornerRadiusDp", 8), 0f, 2500f, 8f);
        key.borderWidthDp = finite(json.optDouble("borderWidthDp", 5), 0f, 500f, 5f);
        key.fillColorUp = json.optInt("fillColorUp", json.optInt("fillColor", key.fillColorUp));
        key.borderColorUp = json.optInt("borderColorUp", json.optInt("borderColor", key.borderColorUp));
        key.textColorUp = json.optInt("textColorUp", key.textColorUp);
        key.countColorUp = json.optInt("countColorUp", key.countColorUp);
        key.fillColorDown = json.optInt("fillColorDown", key.fillColorDown);
        key.borderColorDown = json.optInt("borderColorDown", key.borderColorUp);
        key.textColorDown = json.optInt("textColorDown", key.textColorUp);
        key.countColorDown = json.optInt("countColorDown", key.countColorUp);
        key.textSizeSp = finite(json.optDouble("textSizeSp", 14), 4f, 300f, 14f);
        key.countTextSizeSp = json.has("countTextSizeSp")
                ? finite(json.optDouble("countTextSizeSp", key.textSizeSp), 4f, 300f, key.textSizeSp)
                : -1f;
        key.autoFitText = json.optBoolean("autoFitText", true);
        key.textShadowEnabled = json.optBoolean("textShadowEnabled", false);
        key.textShadowColor = json.optInt("textShadowColor", 0x80000000);
        key.textShadowOffsetXDp = finite(json.optDouble("textShadowOffsetXDp", 1), -500f, 500f, 1f);
        key.textShadowOffsetYDp = finite(json.optDouble("textShadowOffsetYDp", 1), -500f, 500f, 1f);
        key.textShadowBlurRadiusDp = finite(json.optDouble("textShadowBlurRadiusDp", 0), 0f, 100f, 0f);
        key.showCount = json.optBoolean("showCount", true);
        key.fontAsset = limited(json.optString("fontAsset", ""), 128);
        key.imageAsset = limited(json.optString("imageAsset", ""), 128);
        key.imageReplacesLabel = json.optBoolean("imageReplacesLabel", true);
        key.mappingEnabled = json.optBoolean("mappingEnabled", false);
        key.mappedKey = limited(json.optString("mappedKey", ""), 128);
        key.ghostKey = json.optBoolean("ghostKey", false);
        key.rainEnabled = json.optBoolean("rainEnabled", false);
        key.rainOffsetXDp = finite(json.optDouble("rainOffsetXDp", 0), -5000f, 5000f, 0f);
        key.rainOffsetYDp = finite(json.optDouble("rainOffsetYDp", 0), -5000f, 5000f, 0f);
        key.rainWidthPercent = finite(json.optDouble("rainWidthPercent", 100), 1f, 1000f, 100f);
        key.rainColor = json.optInt("rainColor", key.rainColor);
        key.rainSpeedDp = finite(json.optDouble("rainSpeedDp", 5), 0f, 1000f, 5f);
        key.rainGlowEnabled = json.optBoolean("rainGlowEnabled", false);
        key.rainGlowRadiusDp = finite(json.optDouble("rainGlowRadiusDp", 12), 0f, 200f, 12f);
        key.kpsTotalNoWrap = json.optBoolean("kpsTotalNoWrap", false);
        String labelCase = json.optString("counterLabelCase", "upper").toLowerCase(java.util.Locale.ROOT);
        key.counterLabelCase = labelCase.equals("lower") || labelCase.equals("title") ? labelCase : "upper";
        key.textOffsetXDp = finite(json.optDouble("textOffsetXDp", json.optDouble("kpsTotalLabelOffsetX", 0)), -5000f, 5000f, 0f);
        key.textOffsetYDp = finite(json.optDouble("textOffsetYDp", json.optDouble("kpsTotalLabelOffsetY", 0)), -5000f, 5000f, 0f);
        key.countOffsetXDp = finite(json.optDouble("countOffsetXDp", json.optDouble("kpsTotalValueOffsetX", 0)), -5000f, 5000f, 0f);
        key.countOffsetYDp = finite(json.optDouble("countOffsetYDp", json.optDouble("kpsTotalValueOffsetY", 0)), -5000f, 5000f, 0f);
        key.zIndex = boundedInt(json.optInt("zIndex", 0), -100000, 100000);
        return key;
    }

    private static float finite(double value, float min, float max, float fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return (float) Math.max(min, Math.min(max, value));
    }

    private static int boundedInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String limited(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
