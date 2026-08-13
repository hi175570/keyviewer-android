package com.KV.bridge;

/** Fast in-process callback used by the optional accessibility keyboard monitor. */
public interface AccessibilityInputCallback {
    /** State is 0=down, 1=repeat, 2=up. Event time uses Android uptime milliseconds. */
    void onInput(String key, int state, String deviceId, long eventTimeMillis);

    void onStatus(String status);
}
