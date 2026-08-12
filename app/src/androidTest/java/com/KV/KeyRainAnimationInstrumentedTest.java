package com.KV;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class KeyRainAnimationInstrumentedTest {
    @Test
    public void detachedOverlayKeyView_keepsAnimatingRain() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<KeyView> viewRef = new AtomicReference<>();
        AtomicReference<KeyData> keyRef = new AtomicReference<>();
        float[] initialHeight = new float[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            GlobalSettings settings = GlobalSettings.getInstance(context);
            settings.globalRainHeightDp = 200;
            settings.globalRainReserveHeightDp = 10;

            KeyView keyView = new KeyView(context);
            keyView.getKeyManager().getKeys().clear();
            KeyData key = new KeyData("rain-test", 100f, 100f, 50f, 50f);
            key.boundKey = "a";
            key.rainEnabled = true;
            key.rainSpeedDp = 5f;
            keyView.getKeyManager().getKeys().add(key);

            // Deliberately do not attach KeyView to a window: FloatView renders it
            // offscreen in exactly this state.
            keyView.startRefreshing();
            keyView.setKeyPressed("a", true);
            assertEquals(1, key.rainParticles.size());
            initialHeight[0] = key.rainParticles.get(0).height;
            viewRef.set(keyView);
            keyRef.set(key);
        });

        SystemClock.sleep(120);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            KeyData key = keyRef.get();
            assertTrue("Detached overlay rain must advance after its first frame",
                    key.rainParticles.get(0).height > initialHeight[0]);
            viewRef.get().stopRefreshing();
        });
    }
}
