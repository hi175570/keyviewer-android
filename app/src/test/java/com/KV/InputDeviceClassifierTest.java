package com.KV;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

public class InputDeviceClassifierTest {

    @Test
    public void keyboardWithManyKeysIsAccepted() {
        assertTrue(RishInputListener.isKeyboardDevice(deviceInfo(
                "AT Translated Set 2 keyboard", false, 120)));
    }

    @Test
    public void mouseWithManyReportedKeysIsRejected() {
        assertFalse(RishInputListener.isKeyboardDevice(deviceInfo(
                "Feature-rich Gaming Mouse", true, 120)));
    }

    @Test
    public void combinedKeyboardMouseDeviceIsAccepted() {
        assertTrue(RishInputListener.isKeyboardDevice(deviceInfo(
                "USB Keyboard Mouse Combo", true, 120)));
    }

    @Test
    public void virtualInjectionDeviceIsAlwaysRejected() {
        assertFalse(RishInputListener.isKeyboardDevice(deviceInfo(
                "KV Virtual Input", false, 120)));
    }

    @Test
    public void calibrationParserAcceptsOnlyPhysicalKeyDownLines() {
        assertTrue("/dev/input/event13".equals(
                GlobalSettingsPanel.pressedKeyDeviceFromLine(
                        "/dev/input/event13: 0001 001e 00000001")));
        assertTrue(GlobalSettingsPanel.pressedKeyDeviceFromLine(
                "/dev/input/event13: 0001 001e 00000000") == null);
        assertTrue(GlobalSettingsPanel.pressedKeyDeviceFromLine(
                "/dev/input/event11: 0003 0035 00000120") == null);
        assertTrue(GlobalSettingsPanel.pressedKeyDeviceFromLine("garbage") == null);
    }

    private static String deviceInfo(String name, boolean pointer, int keyCount) {
        StringBuilder result = new StringBuilder()
                .append("name:     \"").append(name).append("\"\n")
                .append("events:\n")
                .append("  KEY (0001):");
        for (int code = 1; code <= keyCount; code++) {
            result.append(' ').append(String.format(Locale.ROOT, "%04x", code));
        }
        if (pointer) {
            result.append("\n  REL (0002): 0000 0001");
        }
        return result.toString();
    }
}
