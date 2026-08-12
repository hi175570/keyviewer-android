package com.KV.bridge;

/** Synchronous route callback implemented by the shared C# input core. */
public interface AndroidInputCallback {
    /**
     * Returns the key name to inject. An empty result suppresses the physical key.
     * State is 0=down, 1=repeat, 2=up.
     */
    String route(String key, int state, String deviceId);

    void onStatus(String status);
}
