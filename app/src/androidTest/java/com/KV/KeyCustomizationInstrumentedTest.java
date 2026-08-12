package com.KV;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class KeyCustomizationInstrumentedTest {
    @Test
    public void selectAll_requiresAndPreservesBaselineKey() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            KeyView view = new KeyView(context);
            view.getKeyManager().getKeys().clear();
            KeyData first = new KeyData("baseline-first", 50f, 50f, 40f, 40f);
            KeyData baseline = new KeyData("baseline-selected", 100f, 50f, 40f, 40f);
            KeyData last = new KeyData("baseline-last", 150f, 50f, 40f, 40f);
            view.getKeyManager().getKeys().add(first);
            view.getKeyManager().getKeys().add(baseline);
            view.getKeyManager().getKeys().add(last);
            view.setBatchEditMode(true);

            view.selectAllKeys();
            assertTrue("Select all must be disabled logically until a baseline is selected",
                    view.getSelectedKeys().isEmpty());

            view.getSelectedKeys().add(baseline);
            view.selectAllKeys();
            assertEquals(3, view.getSelectedKeys().size());
            assertSame("The explicitly selected key must remain the batch value baseline",
                    baseline, view.getSelectedKeys().get(0));
        });
    }

    @Test
    public void vectorFontAndGlow_renderTogether() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File assetDirectory = new File(context.getFilesDir(), "key_assets");
        assertTrue(assetDirectory.isDirectory() || assetDirectory.mkdirs());
        String suffix = UUID.randomUUID().toString();
        File vectorFile = new File(assetDirectory, "test-vector-" + suffix + ".xml");
        File fontFile = new File(assetDirectory, "test-font-" + suffix + ".ttf");

        String vector = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "android:width=\"24dp\" android:height=\"24dp\" "
                + "android:viewportWidth=\"24\" android:viewportHeight=\"24\">"
                + "<path android:fillColor=\"#FFFF00FF\" "
                + "android:pathData=\"M2,2h20v20h-20z\"/></vector>";
        try (FileOutputStream output = new FileOutputStream(vectorFile)) {
            output.write(vector.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream input = context.getAssets().open("fonts/adofai.ttf");
             FileOutputStream output = new FileOutputStream(fontFile)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }

        try {
            Drawable drawable = KeyAssetStore.loadDrawable(context, vectorFile.getName());
            assertNotNull(drawable);
            assertNotNull(Typeface.createFromFile(fontFile));

            AtomicReference<Bitmap> rendered = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                KeyView view = createViewWithSingleKey(context);
                KeyData key = view.getKeyManager().getKeys().get(0);
                key.imageAsset = vectorFile.getName();
                key.imageReplacesLabel = true;
                key.fontAsset = fontFile.getName();
                key.rainEnabled = true;
                key.rainGlowEnabled = true;
                key.rainGlowRadiusDp = 14f;
                KeyData.RainParticle particle = new KeyData.RainParticle(
                        180f, 220f, 22f, Color.CYAN, 0f);
                particle.height = 70f;
                key.rainParticles.add(particle);
                rendered.set(render(view));
            });

            assertTrue("The key artwork and glowing rain must produce visible pixels",
                    countVisiblePixels(rendered.get()) > 1000);
        } finally {
            // These exact files were created only for this test.
            //noinspection ResultOfMethodCallIgnored
            vectorFile.delete();
            //noinspection ResultOfMethodCallIgnored
            fontFile.delete();
        }
    }

    @Test
    public void missingReplacementImage_fallsBackToLabel() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<Bitmap> missingImage = new AtomicReference<>();
        AtomicReference<Bitmap> ordinaryLabel = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            KeyView view = createViewWithSingleKey(context);
            KeyData key = view.getKeyManager().getKeys().get(0);
            key.imageAsset = "does-not-exist.xml";
            key.imageReplacesLabel = true;
            missingImage.set(render(view));

            view.clearAssetCaches();
            key.imageAsset = "";
            ordinaryLabel.set(render(view));
        });

        assertTrue("A missing replacement image must not make the key label disappear",
                missingImage.get().sameAs(ordinaryLabel.get()));
    }

    @Test
    public void textShadow_changesRenderedPixels() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<Bitmap> plain = new AtomicReference<>();
        AtomicReference<Bitmap> shadowed = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            KeyView view = createViewWithSingleKey(context);
            KeyData key = view.getKeyManager().getKeys().get(0);
            key.textShadowEnabled = false;
            plain.set(render(view));

            key.textShadowEnabled = true;
            key.textShadowColor = Color.MAGENTA;
            key.textShadowOffsetXDp = 5f;
            key.textShadowOffsetYDp = 5f;
            key.textShadowBlurRadiusDp = 2f;
            shadowed.set(render(view));
        });

        assertFalse("Enabling text shadow must change the rendered key pixels",
                plain.get().sameAs(shadowed.get()));
    }

    @Test
    public void ordinaryKey_textAndCountLayoutAreIndependent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<Bitmap> baseline = new AtomicReference<>();
        AtomicReference<Bitmap> textChanged = new AtomicReference<>();
        AtomicReference<Bitmap> countChanged = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            KeyView view = createViewWithSingleKey(context);
            KeyData key = view.getKeyManager().getKeys().get(0);
            key.showCount = true;
            baseline.set(render(view));

            key.textOffsetXDp = 24f;
            textChanged.set(render(view));

            key.textOffsetXDp = 0f;
            key.countOffsetYDp = -18f;
            key.countTextSizeSp = 28f;
            countChanged.set(render(view));
        });

        assertFalse("An ordinary key's label offset must affect rendering",
                baseline.get().sameAs(textChanged.get()));
        assertFalse("An ordinary key's count offset and size must affect rendering",
                baseline.get().sameAs(countChanged.get()));
    }

    private static KeyView createViewWithSingleKey(Context context) {
        KeyView view = new KeyView(context);
        view.layout(0, 0, 480, 320);
        view.getKeyManager().getKeys().clear();
        view.clearSelection();
        KeyData key = new KeyData("customization-test", 160f, 150f, 180f, 120f);
        key.boundKey = "a";
        key.displayLabel = "VISIBLE";
        key.fillColorUp = Color.rgb(40, 50, 60);
        key.borderColorUp = Color.WHITE;
        key.textColorUp = Color.YELLOW;
        key.showCount = false;
        view.getKeyManager().getKeys().add(key);
        return view;
    }

    private static Bitmap render(KeyView view) {
        Bitmap bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        view.render(new Canvas(bitmap), false);
        return bitmap;
    }

    private static int countVisiblePixels(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) count++;
            }
        }
        return count;
    }
}
