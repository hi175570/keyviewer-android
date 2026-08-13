package com.KV.bridge;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Observes physical keyboard events and always leaves the system event stream untouched. */
public final class KeyViewerAccessibilityService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityInputBridge.serviceConnected();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        int state = action == KeyEvent.ACTION_UP ? 2 : event.getRepeatCount() == 0 ? 0 : 1;
        AccessibilityInputBridge.dispatch(
                event.getKeyCode(), state, event.getDeviceId(), event.getEventTime());
        // Accessibility mode is deliberately observation-only. It never consumes or reinjects.
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        AccessibilityInputBridge.serviceDisconnected();
        super.onDestroy();
    }
}
