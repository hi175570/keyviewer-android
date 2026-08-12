package com.KV;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class PortableConfigAssetsInstrumentedTest {

    @Test
    public void bundledDefaultConfig_usesBuiltInMaplestoryFont() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject root;
        try (InputStream input = context.getAssets().open("default_config.json");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            root = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }

        assertEquals(4, root.getInt("formatVersion"));
        assertEquals(12, root.getJSONArray("keys").length());
        PortableConfigAssets.RestoreSession session =
                PortableConfigAssets.restore(context, root);
        try {
            assertFalse(root.has(PortableConfigAssets.BUNDLE_KEY));
            String fontName = root.getJSONArray("keys").getJSONObject(0)
                    .getString("fontAsset");
            assertEquals("", fontName);
            assertNotNull(Typeface.createFromAsset(
                    context.getAssets(), "fonts/maplestory_otf_bold.otf"));
        } finally {
            session.rollback();
        }
    }

    @Test
    public void embeddedVector_survivesOriginalFileRemovalAndGetsNewPrivateName()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File original = createVector(context, "portable-original-");
        JSONObject root = configWithImages(original.getName(), original.getName());
        File restored = null;
        try {
            PortableConfigAssets.embed(context, root);
            assertEquals(4, root.getInt("formatVersion"));
            assertEquals(1, root.getJSONObject(PortableConfigAssets.BUNDLE_KEY)
                    .getJSONArray("items").length());
            assertTrue(original.delete());

            PortableConfigAssets.RestoreSession session = PortableConfigAssets.restore(context, root);
            String first = root.getJSONArray("keys").getJSONObject(0).getString("imageAsset");
            String second = root.getJSONArray("keys").getJSONObject(1).getString("imageAsset");
            assertEquals(first, second);
            assertFalse(first.equals(original.getName()));
            restored = KeyAssetStore.resolve(context, first);
            assertNotNull(restored);
            assertNotNull(KeyAssetStore.loadDrawable(context, first));
            assertFalse(root.has(PortableConfigAssets.BUNDLE_KEY));
            session.commit();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
            if (restored != null) {
                //noinspection ResultOfMethodCallIgnored
                restored.delete();
            }
        }
    }

    @Test
    public void checksumFailure_rollsBackAssetsRestoredEarlierInTheBundle() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File first = createVector(context, "portable-first-");
        File second = createVector(context, "portable-second-");
        JSONObject root = configWithImages(first.getName(), second.getName());
        File directory = first.getParentFile();
        try {
            PortableConfigAssets.embed(context, root);
            JSONArray items = root.getJSONObject(PortableConfigAssets.BUNDLE_KEY)
                    .getJSONArray("items");
            items.getJSONObject(1).put("sha256",
                    "0000000000000000000000000000000000000000000000000000000000000000");
            assertTrue(first.delete());
            assertTrue(second.delete());
            int filesBefore = fileCount(directory);
            try {
                PortableConfigAssets.restore(context, root);
                fail("A modified asset must fail checksum validation");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().contains("checksum"));
            }
            assertEquals(filesBefore, fileCount(directory));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            first.delete();
            //noinspection ResultOfMethodCallIgnored
            second.delete();
        }
    }

    @Test
    public void embeddedFont_survivesOriginalFileRemoval() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File original = createFont(context);
        JSONObject root = new JSONObject().put("formatVersion", 3).put("keys",
                new JSONArray().put(new JSONObject()
                        .put("id", "font-key")
                        .put("fontAsset", original.getName())
                        .put("imageAsset", "")));
        File restored = null;
        try {
            PortableConfigAssets.embed(context, root);
            assertTrue(original.delete());
            PortableConfigAssets.RestoreSession session = PortableConfigAssets.restore(context, root);
            String restoredName = root.getJSONArray("keys").getJSONObject(0)
                    .getString("fontAsset");
            restored = KeyAssetStore.resolve(context, restoredName);
            assertNotNull(restored);
            assertNotNull(Typeface.createFromFile(restored));
            session.commit();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
            if (restored != null) {
                //noinspection ResultOfMethodCallIgnored
                restored.delete();
            }
        }
    }

    @Test
    public void globalBackgroundImage_isNotEmbeddedWithLayout() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File original = createVector(context, "portable-background-");
        JSONObject root = new JSONObject()
                .put("formatVersion", 4)
                .put("globalSettings", new JSONObject()
                        .put("backgroundImageAsset", original.getName()))
                .put("keys", new JSONArray());
        try {
            PortableConfigAssets.embed(context, root);
            JSONArray items = root.getJSONObject(PortableConfigAssets.BUNDLE_KEY)
                    .getJSONArray("items");
            assertEquals(0, items.length());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
        }
    }

    @Test
    public void sharedPortableConfig_isReadableThroughFileProvider() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "shared_configs");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "provider-test-" + UUID.randomUUID() + ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("{\"formatVersion\":4}".getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            assertEquals("content", uri.getScheme());
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                assertNotNull(input);
                assertTrue(input.read() >= 0);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static JSONObject configWithImages(String firstName, String secondName)
            throws Exception {
        JSONArray keys = new JSONArray();
        keys.put(new JSONObject()
                .put("id", "first")
                .put("fontAsset", "")
                .put("imageAsset", firstName));
        keys.put(new JSONObject()
                .put("id", "second")
                .put("fontAsset", "")
                .put("imageAsset", secondName));
        return new JSONObject().put("formatVersion", 3).put("keys", keys);
    }

    private static File createVector(Context context, String prefix) throws Exception {
        File directory = new File(context.getFilesDir(), "key_assets");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, prefix + UUID.randomUUID() + ".xml");
        String vector = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "android:width=\"24dp\" android:height=\"24dp\" "
                + "android:viewportWidth=\"24\" android:viewportHeight=\"24\">"
                + "<path android:fillColor=\"#FF00FF00\" "
                + "android:pathData=\"M2,2h20v20h-20z\"/></vector>";
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(vector.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static File createFont(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "key_assets");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "portable-font-" + UUID.randomUUID() + ".ttf");
        try (InputStream input = context.getAssets().open("fonts/adofai.ttf");
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return file;
    }

    private static int fileCount(File directory) {
        File[] files = directory.listFiles();
        return files == null ? 0 : files.length;
    }
}
