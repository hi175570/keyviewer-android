package com.KV;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class ConfigCodecInstrumentedTest {
    @Test
    public void encodedLayoutDoesNotContainGlobalSettings() throws Exception {
        JSONObject root = ConfigCodec.encode(new KeyManager(), 1f);

        assertFalse(root.has("globalSettings"));
    }

    @Test
    public void perKeyTextAndCountLayoutRoundTrips() throws Exception {
        KeyManager source = new KeyManager();
        KeyData key = new KeyData("counter", 100, 100, 50, 50);
        key.displayLabel = "KPS";
        key.textOffsetXDp = 1;
        key.textOffsetYDp = 2;
        key.countOffsetXDp = 3;
        key.countOffsetYDp = 4;
        key.countTextSizeSp = 23;
        key.counterLabelCase = "title";
        source.getKeys().add(key);

        JSONObject root = ConfigCodec.encode(source, 1f);
        KeyManager target = new KeyManager();
        ConfigCodec.apply(root, target, 1f);

        KeyData loaded = target.getKeys().get(0);
        assertEquals(1f, loaded.textOffsetXDp, 0f);
        assertEquals(2f, loaded.textOffsetYDp, 0f);
        assertEquals(3f, loaded.countOffsetXDp, 0f);
        assertEquals(4f, loaded.countOffsetYDp, 0f);
        assertEquals(23f, loaded.countTextSizeSp, 0f);
        assertEquals("title", loaded.counterLabelCase);
    }

    @Test
    public void textAppearanceRoundTrips() throws Exception {
        KeyManager source = new KeyManager();
        KeyData key = new KeyData("shadow", 100, 100, 50, 50);
        key.autoFitText = false;
        key.textShadowEnabled = true;
        key.textShadowColor = 0x80010203;
        key.textShadowOffsetXDp = 2;
        key.textShadowOffsetYDp = 3;
        key.textShadowBlurRadiusDp = 4;
        source.getKeys().add(key);

        JSONObject root = ConfigCodec.encode(source, 1f);
        KeyManager target = new KeyManager();
        ConfigCodec.apply(root, target, 1f);

        KeyData loaded = target.getKeys().get(0);
        assertFalse(loaded.autoFitText);
        org.junit.Assert.assertTrue(loaded.textShadowEnabled);
        assertEquals(0x80010203, loaded.textShadowColor);
        assertEquals(2f, loaded.textShadowOffsetXDp, 0f);
        assertEquals(3f, loaded.textShadowOffsetYDp, 0f);
        assertEquals(4f, loaded.textShadowBlurRadiusDp, 0f);
    }

    @Test
    public void legacyCounterOffsetsMigrateToPerKeyOffsets() throws Exception {
        JSONObject root = new JSONObject("{\"keys\":[{\"id\":\"legacy\",\"boundKey\":\"a\","
                + "\"kpsTotalLabelOffsetX\":5,\"kpsTotalLabelOffsetY\":6,"
                + "\"kpsTotalValueOffsetX\":7,\"kpsTotalValueOffsetY\":8}]}");
        KeyManager target = new KeyManager();
        ConfigCodec.apply(root, target, 1f);

        KeyData loaded = target.getKeys().get(0);
        assertEquals(5f, loaded.textOffsetXDp, 0f);
        assertEquals(6f, loaded.textOffsetYDp, 0f);
        assertEquals(7f, loaded.countOffsetXDp, 0f);
        assertEquals(8f, loaded.countOffsetYDp, 0f);
    }

    @Test
    public void loadedLayoutMovesAsOneGroupToNearestGrid() throws Exception {
        JSONObject root = new JSONObject("{\"keys\":["
                + "{\"id\":\"a\",\"centerX\":17.25,\"centerY\":32.25},"
                + "{\"id\":\"b\",\"centerX\":47.25,\"centerY\":62.25},"
                + "{\"id\":\"c\",\"centerX\":77.25,\"centerY\":92.25}]}");
        KeyManager manager = new KeyManager();

        ConfigCodec.GridAlignmentResult result = ConfigCodec.apply(root, manager, 1f);

        assertEquals(-2.25f, result.offsetX, 0.0001f);
        assertEquals(-2.25f, result.offsetY, 0.0001f);
        assertEquals(3, result.groupMovedKeyCount);
        assertEquals(0, result.individuallyAdjustedKeyCount);
        assertEquals(30f,
                manager.getKeys().get(1).centerX - manager.getKeys().get(0).centerX,
                0.0001f);
        assertEquals(30f,
                manager.getKeys().get(1).centerY - manager.getKeys().get(0).centerY,
                0.0001f);
    }

    @Test
    public void loadedLayoutRepairsOnlyResidualLegacyDriftAfterGroupMove() throws Exception {
        JSONObject root = new JSONObject("{\"keys\":["
                + "{\"id\":\"a\",\"centerX\":17.25,\"centerY\":32.25},"
                + "{\"id\":\"b\",\"centerX\":48.10,\"centerY\":61.80}]}");
        KeyManager manager = new KeyManager();

        ConfigCodec.GridAlignmentResult result = ConfigCodec.apply(root, manager, 1f);

        assertEquals(2, result.groupMovedKeyCount);
        assertEquals(1, result.individuallyAdjustedKeyCount);
        for (KeyData key : manager.getKeys()) {
            assertEquals(0f, key.centerX % 15f, 0.0001f);
            assertEquals(0f, key.centerY % 15f, 0.0001f);
        }
    }

    @Test
    public void bundledJipperPresetsAreCompleteAndGridAligned() throws Exception {
        String[] assets = {"preset_jipper_16k.json", "preset_jipper_12k.json", "preset_jipper_10k.json"};
        int[] expectedCounts = {18, 14, 12};
        for (int preset = 0; preset < assets.length; preset++) {
            JSONObject root = new JSONObject(readAsset(assets[preset]));
            assertFalse(root.has("globalSettings"));
            assertEquals(1, root.getJSONObject("embeddedAssets").getJSONArray("items").length());
            KeyManager manager = new KeyManager();
            ConfigCodec.apply(root, manager, 1f);
            assertEquals(expectedCounts[preset], manager.getKeys().size());
            assertEquals(2, manager.getKeys().stream().filter(KeyData::isSpecialKey).count());
            for (KeyData key : manager.getKeys()) {
                assertEquals(0f, key.centerX % 15f, 0f);
                assertEquals(0f, key.centerY % 15f, 0f);
                org.junit.Assert.assertTrue(key.countTextSizeSp > 0f);
            }
        }
    }

    private static String readAsset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
