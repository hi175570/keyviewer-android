package com.KV.bridge;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local bridge between Android's AccessibilityService and the shared C# input core.
 *
 * Key names are built once when this class is loaded and device labels are cached. The hot key
 * callback does not read preferences, rebuild mappings, post delayed work, or allocate a HashMap.
 */
public final class AccessibilityInputBridge implements AutoCloseable {
    private static final AtomicReference<AccessibilityInputCallback> ACTIVE_CALLBACK =
            new AtomicReference<>();
    private static final ConcurrentHashMap<Integer, String> DEVICE_IDS = new ConcurrentHashMap<>();
    private static final String[] KEY_NAMES = createKeyNames();
    private static volatile boolean serviceConnected;

    private final Context context;
    private final AccessibilityInputCallback callback;
    private volatile boolean started;

    public AccessibilityInputBridge(Context context, AccessibilityInputCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        started = true;
        ACTIVE_CALLBACK.set(callback);
        publishStatus(serviceConnected ? "accessibility_listening"
                : isServiceEnabled() ? "accessibility_waiting"
                : "accessibility_permission_required");
    }

    public void stop() {
        started = false;
        ACTIVE_CALLBACK.compareAndSet(callback, null);
        publishStatus("stopped");
    }

    public boolean isRunning() {
        return started && serviceConnected && ACTIVE_CALLBACK.get() == callback;
    }

    public boolean isServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(context, KeyViewerAccessibilityService.class);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            if (service.getResolveInfo() == null || service.getResolveInfo().serviceInfo == null) {
                continue;
            }
            ComponentName actual = new ComponentName(
                    service.getResolveInfo().serviceInfo.packageName,
                    service.getResolveInfo().serviceInfo.name);
            if (expected.equals(actual)) return true;
        }
        return false;
    }

    public void openSettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public void refreshStatus() {
        if (!started) return;
        publishStatus(serviceConnected ? "accessibility_listening"
                : isServiceEnabled() ? "accessibility_waiting"
                : "accessibility_permission_required");
    }

    @Override public void close() {
        stop();
    }

    static void serviceConnected() {
        serviceConnected = true;
        AccessibilityInputCallback callback = ACTIVE_CALLBACK.get();
        if (callback != null) safeStatus(callback, "accessibility_listening");
    }

    static void serviceDisconnected() {
        serviceConnected = false;
        AccessibilityInputCallback callback = ACTIVE_CALLBACK.get();
        if (callback != null) safeStatus(callback, "accessibility_disconnected");
    }

    static boolean dispatch(int keyCode, int state, int deviceId, long eventTimeMillis) {
        AccessibilityInputCallback callback = ACTIVE_CALLBACK.get();
        if (callback == null || keyCode < 0 || keyCode >= KEY_NAMES.length) return false;
        String key = KEY_NAMES[keyCode];
        if (key == null || key.isEmpty()) return false;
        String source = DEVICE_IDS.computeIfAbsent(deviceId,
                id -> "android:accessibility:" + id);
        try {
            callback.onInput(key, state, source, eventTimeMillis);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void publishStatus(String status) {
        safeStatus(callback, status);
    }

    private static void safeStatus(AccessibilityInputCallback callback, String status) {
        try { callback.onStatus(status); } catch (Throwable ignored) { }
    }

    private static String[] createKeyNames() {
        int maximum = Math.max(300, KeyEvent.getMaxKeyCode());
        String[] names = new String[maximum + 1];
        for (int code = 0; code <= maximum; code++) names[code] = canonicalName(code);
        return names;
    }

    private static String canonicalName(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('a' + code - KeyEvent.KEYCODE_A));
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0));
        }
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) {
            return "f" + (code - KeyEvent.KEYCODE_F1 + 1);
        }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            return "num" + (code - KeyEvent.KEYCODE_NUMPAD_0);
        }
        switch (code) {
            case KeyEvent.KEYCODE_ESCAPE: return "esc";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "del";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_ENTER: return "enter";
            case KeyEvent.KEYCODE_SPACE: return "space";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "lctrl";
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "rctrl";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "lshift";
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "rshift";
            case KeyEvent.KEYCODE_ALT_LEFT: return "lalt";
            case KeyEvent.KEYCODE_ALT_RIGHT: return "ralt";
            case KeyEvent.KEYCODE_META_LEFT: return "lmeta";
            case KeyEvent.KEYCODE_META_RIGHT: return "rmeta";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "capslock";
            case KeyEvent.KEYCODE_NUM_LOCK: return "numlock";
            case KeyEvent.KEYCODE_SCROLL_LOCK: return "scrolllock";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_MOVE_HOME: return "home";
            case KeyEvent.KEYCODE_MOVE_END: return "end";
            case KeyEvent.KEYCODE_INSERT: return "insert";
            case KeyEvent.KEYCODE_MINUS: return "minus";
            case KeyEvent.KEYCODE_EQUALS: return "equal";
            case KeyEvent.KEYCODE_LEFT_BRACKET: return "bracketleft";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return "bracketright";
            case KeyEvent.KEYCODE_BACKSLASH: return "backslash";
            case KeyEvent.KEYCODE_SEMICOLON: return "semicolon";
            case KeyEvent.KEYCODE_APOSTROPHE: return "quote";
            case KeyEvent.KEYCODE_GRAVE: return "backquote";
            case KeyEvent.KEYCODE_COMMA: return "comma";
            case KeyEvent.KEYCODE_PERIOD: return "dot";
            case KeyEvent.KEYCODE_SLASH: return "slash";
            case KeyEvent.KEYCODE_PAGE_UP: return "pageup";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "pagedown";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return "numdivide";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return "nummultiply";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return "numminus";
            case KeyEvent.KEYCODE_NUMPAD_ADD: return "numplus";
            case KeyEvent.KEYCODE_NUMPAD_DOT: return "numdot";
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "numenter";
            case KeyEvent.KEYCODE_NUMPAD_EQUALS: return "numequal";
            case KeyEvent.KEYCODE_SYSRQ: return "printscreen";
            case KeyEvent.KEYCODE_BREAK: return "pause";
            default:
                String symbolic = KeyEvent.keyCodeToString(code);
                if (symbolic == null || "KEYCODE_UNKNOWN".equals(symbolic)) return null;
                String prefix = "KEYCODE_";
                return (symbolic.startsWith(prefix) ? symbolic.substring(prefix.length()) : symbolic)
                        .toLowerCase(Locale.ROOT);
        }
    }
}
