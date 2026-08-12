package com.KV;

import java.util.HashMap;
import java.util.Map;

/** Detects a second press that occurs implausibly soon after the same physical key was released. */
final class KeyDebounceFilter {
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final Map<String, Long> lastReleaseNanos = new HashMap<>();

    synchronized boolean shouldSuppressDown(
            String physicalKey, long nowNanos, boolean enabled, int windowMillis) {
        if (!enabled) return false;
        Long releasedAt = lastReleaseNanos.get(physicalKey);
        if (releasedAt == null || nowNanos < releasedAt) return false;
        long safeWindowMillis = Math.max(1, Math.min(200, windowMillis));
        return nowNanos - releasedAt < safeWindowMillis * NANOS_PER_MILLISECOND;
    }

    synchronized void recordRelease(String physicalKey, long nowNanos) {
        lastReleaseNanos.put(physicalKey, nowNanos);
    }

    synchronized void clear() {
        lastReleaseNanos.clear();
    }
}
