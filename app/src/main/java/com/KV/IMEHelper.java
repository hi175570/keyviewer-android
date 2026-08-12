package com.KV;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

public final class IMEHelper {

    private static final String TAG = "IMEHelper";

    private IMEHelper() {}

    public static boolean isKeyViewerIMEEnabled(Context context) {
        InputMethodManager manager = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager == null) {
            return false;
        }
        List<InputMethodInfo> enabled = manager.getEnabledInputMethodList();
        for (InputMethodInfo ime : enabled) {
            if (ime.getComponent().getClassName().equals(KeyMappingIME.class.getName())) {
                return true;
            }
        }
        return false;
    }

    public static void openIMESettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to open input method settings", e);
        }
    }

    public static void showInputMethodPicker(Context context) {
        InputMethodManager manager = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.showInputMethodPicker();
        }
    }
}
