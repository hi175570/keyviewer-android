package com.KV;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class KeyboardDiscoveryOrderTest {
    @Test
    public void calibratedKeyboard_isPreferredWithoutDroppingOtherKeyboards() {
        List<String> result = RishInputListener.prioritizeCalibratedDevice(
                Arrays.asList("/dev/input/event2", "/dev/input/event5"),
                "/dev/input/event5");

        assertEquals(Arrays.asList("/dev/input/event5", "/dev/input/event2"), result);
    }

    @Test
    public void missingCalibration_keepsAllDiscoveredKeyboards() {
        List<String> result = RishInputListener.prioritizeCalibratedDevice(
                Arrays.asList("/dev/input/event2", "/dev/input/event5"),
                "/dev/input/event9");

        assertEquals(Arrays.asList("/dev/input/event2", "/dev/input/event5"), result);
    }
}
