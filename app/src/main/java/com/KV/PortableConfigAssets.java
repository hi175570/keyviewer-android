package com.KV;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds private key artwork/fonts to exported JSON and restores them transactionally. */
final class PortableConfigAssets {
    static final String BUNDLE_KEY = "embeddedAssets";
    private static final int BUNDLE_VERSION = 1;
    private static final int MAX_ASSET_COUNT = 256;
    private static final long MAX_TOTAL_ASSET_BYTES = 48L * 1024L * 1024L;

    private PortableConfigAssets() {}

    static void embed(Context context, JSONObject root) throws IOException, JSONException {
        JSONArray keys = root.optJSONArray("keys");
        if (keys == null) throw new JSONException("Missing keys array");

        Map<String, String> references = new LinkedHashMap<>();
        for (int index = 0; index < keys.length(); index++) {
            JSONObject key = keys.optJSONObject(index);
            if (key == null) throw new JSONException("Invalid key at index " + index);
            collectReference(references, key.optString("fontAsset", ""),
                    KeyAssetStore.TYPE_FONT);
            collectReference(references, key.optString("imageAsset", ""),
                    KeyAssetStore.TYPE_IMAGE);
        }
        if (references.size() > MAX_ASSET_COUNT) {
            throw new IOException("Configuration uses more than " + MAX_ASSET_COUNT + " assets");
        }

        JSONArray items = new JSONArray();
        long totalBytes = 0;
        for (Map.Entry<String, String> reference : references.entrySet()) {
            File file = KeyAssetStore.resolve(context, reference.getKey());
            if (file == null) {
                throw new IOException("Referenced asset is missing: " + reference.getKey());
            }
            byte[] bytes = readAsset(file);
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_ASSET_BYTES) {
                throw new IOException("Embedded assets exceed 48 MiB total");
            }

            JSONObject item = new JSONObject();
            item.put("name", reference.getKey());
            item.put("type", reference.getValue());
            item.put("extension", KeyAssetStore.extensionOfStoredName(reference.getKey()));
            item.put("size", bytes.length);
            item.put("sha256", sha256(bytes));
            item.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            items.put(item);
        }

        JSONObject bundle = new JSONObject();
        bundle.put("version", BUNDLE_VERSION);
        bundle.put("items", items);
        root.put(BUNDLE_KEY, bundle);
        root.put("formatVersion", 4);
    }

    static RestoreSession restore(Context context, JSONObject root)
            throws IOException, JSONException {
        JSONObject bundle = root.optJSONObject(BUNDLE_KEY);
        if (bundle == null) return new RestoreSession(new ArrayList<>());
        if (bundle.optInt("version", -1) != BUNDLE_VERSION) {
            throw new IOException("Unsupported embedded asset bundle version");
        }
        JSONArray items = bundle.optJSONArray("items");
        if (items == null || items.length() > MAX_ASSET_COUNT) {
            throw new IOException("Invalid embedded asset list");
        }

        Map<String, JSONObject> assets = new LinkedHashMap<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) throw new IOException("Invalid embedded asset entry");
            String name = item.optString("name", "");
            if (!name.matches("[A-Za-z0-9._-]+") || assets.put(name, item) != null) {
                throw new IOException("Invalid or duplicate embedded asset name");
            }
        }

        RestoreSession session = new RestoreSession(new ArrayList<>());
        Map<String, String> restoredNames = new LinkedHashMap<>();
        long[] totalBytes = {0};
        try {
            JSONArray keys = root.optJSONArray("keys");
            if (keys == null) throw new JSONException("Missing keys array");
            for (int index = 0; index < keys.length(); index++) {
                JSONObject key = keys.optJSONObject(index);
                if (key == null) throw new JSONException("Invalid key at index " + index);
                restoreReference(context, key, "fontAsset", KeyAssetStore.TYPE_FONT,
                        assets, restoredNames, session, totalBytes);
                restoreReference(context, key, "imageAsset", KeyAssetStore.TYPE_IMAGE,
                        assets, restoredNames, session, totalBytes);
            }
            root.remove(BUNDLE_KEY);
            return session;
        } catch (IOException | JSONException | RuntimeException e) {
            session.rollback();
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof JSONException) throw (JSONException) e;
            throw new IOException("Unable to restore embedded assets", e);
        }
    }

    private static void collectReference(Map<String, String> references, String name, String type)
            throws IOException {
        if (name == null || name.isEmpty()) return;
        if (KeyAssetStore.TYPE_FONT.equals(type) && FontReference.isLogical(name)) return;
        if (!name.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid stored asset name");
        }
        String previousType = references.putIfAbsent(name, type);
        if (previousType != null && !previousType.equals(type)) {
            throw new IOException("One asset is used as both an image and a font: " + name);
        }
    }

    private static void restoreReference(Context context, JSONObject key, String field,
                                         String expectedType, Map<String, JSONObject> assets,
                                         Map<String, String> restoredNames, RestoreSession session,
                                         long[] totalBytes) throws IOException, JSONException {
        String originalName = key.optString(field, "");
        if (originalName.isEmpty()) return;
        if (KeyAssetStore.TYPE_FONT.equals(expectedType) && FontReference.isLogical(originalName)) {
            return;
        }
        String restoredName = restoredNames.get(originalName);
        if (restoredName == null) {
            JSONObject item = assets.get(originalName);
            if (item == null) {
                throw new IOException("Portable config is missing asset: " + originalName);
            }
            String type = item.optString("type", "");
            if (!expectedType.equals(type)) {
                throw new IOException("Embedded asset type does not match " + field);
            }
            byte[] bytes = decodeAndVerify(item, totalBytes);
            restoredName = KeyAssetStore.importEmbeddedAsset(context, bytes,
                    item.optString("extension", ".bin"), type);
            File restoredFile = KeyAssetStore.resolve(context, restoredName);
            if (restoredFile == null) throw new IOException("Restored asset disappeared");
            session.createdFiles.add(restoredFile);
            restoredNames.put(originalName, restoredName);
        }
        key.put(field, restoredName);
    }

    private static byte[] decodeAndVerify(JSONObject item, long[] totalBytes) throws IOException {
        int declaredSize = item.optInt("size", -1);
        if (declaredSize <= 0 || declaredSize > KeyAssetStore.MAX_ASSET_BYTES) {
            throw new IOException("Embedded asset has an invalid declared size");
        }
        String encoded = item.optString("base64", "");
        long maximumEncodedLength = ((long) declaredSize + 2L) / 3L * 4L + 4L;
        if (encoded.isEmpty() || encoded.length() > maximumEncodedLength) {
            throw new IOException("Embedded asset has invalid Base64 length");
        }
        byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("Embedded asset contains invalid Base64", e);
        }
        if (bytes.length != declaredSize) throw new IOException("Embedded asset size mismatch");
        totalBytes[0] += bytes.length;
        if (totalBytes[0] > MAX_TOTAL_ASSET_BYTES) {
            throw new IOException("Embedded assets exceed 48 MiB total");
        }
        String expectedHash = item.optString("sha256", "");
        if (!expectedHash.equalsIgnoreCase(sha256(bytes))) {
            throw new IOException("Embedded asset checksum mismatch");
        }
        return bytes;
    }

    private static byte[] readAsset(File file) throws IOException {
        if (file.length() <= 0 || file.length() > KeyAssetStore.MAX_ASSET_BYTES) {
            throw new IOException("Asset has an invalid size: " + file.getName());
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > KeyAssetStore.MAX_ASSET_BYTES) {
                    throw new IOException("Asset exceeds 12 MiB: " + file.getName());
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    static final class RestoreSession {
        private final List<File> createdFiles;
        private boolean finished;

        private RestoreSession(List<File> createdFiles) {
            this.createdFiles = createdFiles;
        }

        void commit() {
            finished = true;
            createdFiles.clear();
        }

        void rollback() {
            if (finished) return;
            finished = true;
            for (File file : createdFiles) {
                // These UUID-named files were created only for this import attempt.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            createdFiles.clear();
        }
    }
}
