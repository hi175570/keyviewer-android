package com.KV;

/** Separates the physical key shown by KeyViewer from its optional mapped output. */
final class KeyDispatchRoute {
    final String visualKey;
    final String mappedOutputKey;
    /** The one key to inject, or null when the physical key must be swallowed. */
    final String outputKey;

    private KeyDispatchRoute(String visualKey, String mappedOutputKey, String outputKey) {
        this.visualKey = visualKey;
        this.mappedOutputKey = mappedOutputKey;
        this.outputKey = outputKey;
    }

    static KeyDispatchRoute resolve(String physicalKey, String mappedKey) {
        return resolve(physicalKey, mappedKey, false);
    }

    static KeyDispatchRoute resolve(String physicalKey, String mappedKey, boolean ghostKey) {
        if (physicalKey == null || physicalKey.isEmpty()) {
            throw new IllegalArgumentException("physicalKey must not be empty");
        }
        String mappedOutput = mappedKey != null && !mappedKey.equals(physicalKey)
                ? mappedKey
                : null;
        String output = ghostKey ? null : (mappedOutput != null ? mappedOutput : physicalKey);
        return new KeyDispatchRoute(physicalKey, mappedOutput, output);
    }
}
