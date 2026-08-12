package com.KV;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KeyDebounceFilterTest {
    @Test
    public void secondPressInsideWindow_isSuppressed() {
        KeyDebounceFilter filter = new KeyDebounceFilter();
        filter.recordRelease("/dev/input/event2:30", 1_000_000_000L);

        assertTrue(filter.shouldSuppressDown(
                "/dev/input/event2:30", 1_019_999_999L, true, 20));
        assertFalse(filter.shouldSuppressDown(
                "/dev/input/event2:30", 1_020_000_000L, true, 20));
    }

    @Test
    public void devicesAndKeys_doNotDebounceEachOther() {
        KeyDebounceFilter filter = new KeyDebounceFilter();
        filter.recordRelease("/dev/input/event2:30", 1_000_000_000L);

        assertFalse(filter.shouldSuppressDown(
                "/dev/input/event3:30", 1_001_000_000L, true, 20));
        assertFalse(filter.shouldSuppressDown(
                "/dev/input/event2:31", 1_001_000_000L, true, 20));
    }

    @Test
    public void disabledFilter_neverSuppresses() {
        KeyDebounceFilter filter = new KeyDebounceFilter();
        filter.recordRelease("key", 1_000_000_000L);

        assertFalse(filter.shouldSuppressDown("key", 1_000_000_001L, false, 20));
    }
}
