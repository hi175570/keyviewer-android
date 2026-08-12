package com.KV;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class ActiveConfigInstrumentedTest {
    @Test
    public void launchAutomaticallyLoadsLastActiveConfiguration() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "kv_configs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create config directory");
        }

        KeyManager source = new KeyManager();
        KeyData expected = new KeyData("autoload-marker", 120, 120, 50, 50);
        expected.displayLabel = "AUTOLOAD_MARKER";
        source.getKeys().add(expected);
        JSONObject root = ConfigCodec.encode(source, 1f);
        File configFile = new File(directory, "autoload-check.json");
        try (FileOutputStream output = new FileOutputStream(configFile)) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        context.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE)
                .edit().putString("current_config_name", "autoload-check").commit();

        KeyManager.getInstance(context).replaceAllData(
                Collections.emptyList(), Collections.emptyMap(), 0);
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (!containsMarker(KeyManager.getInstance(context))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            KeyData loaded = KeyManager.getInstance(context).getKeys().stream()
                    .filter(key -> "autoload-marker".equals(key.id)).findFirst().orElse(null);
            assertEquals("AUTOLOAD_MARKER", loaded == null ? null : loaded.displayLabel);
        } finally {
            activity.finish();
            context.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE)
                    .edit().putString("current_config_name", "default").commit();
            //noinspection ResultOfMethodCallIgnored
            configFile.delete();
        }
    }

    private static boolean containsMarker(KeyManager manager) {
        return manager.getKeys().stream().anyMatch(key -> "autoload-marker".equals(key.id));
    }
}
