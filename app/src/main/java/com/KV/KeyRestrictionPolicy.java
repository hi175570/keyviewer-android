package com.KV;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Highest-priority filter applied before KV visuals, mapping, ghosting and debounce output. */
final class KeyRestrictionPolicy {
    private static final Set<String> PROTECTED_KEYS = new LinkedHashSet<>();

    static {
        PROTECTED_KEYS.add("escape");
        PROTECTED_KEYS.add("lmeta");
        PROTECTED_KEYS.add("rmeta");
    }

    private KeyRestrictionPolicy() {}

    static boolean shouldBlock(String keyName, boolean keyIsVisibleInKv,
                               boolean enabled, String forcedBlockedKeys) {
        if (!enabled) return false;
        String key = normalizeKeyName(keyName);
        if (key.isEmpty() || isProtectedKey(key)) return false;
        if (parseBlockedKeys(forcedBlockedKeys).contains(key)) return true;
        return !keyIsVisibleInKv;
    }

    static boolean shouldBlockUnknownCode(int linuxKeyCode, boolean enabled) {
        return enabled && !isProtectedCode(linuxKeyCode);
    }

    static boolean isProtectedCode(int linuxKeyCode) {
        return linuxKeyCode == 1 || linuxKeyCode == 125 || linuxKeyCode == 126;
    }

    static String normalizeBlockedKeys(String value) {
        return String.join(", ", parseBlockedKeys(value));
    }

    private static Set<String> parseBlockedKeys(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) return result;
        for (String token : value.split("[\\s,，;；]+")) {
            String normalized = normalizeKeyName(token);
            if (!normalized.isEmpty() && !isProtectedKey(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static boolean isProtectedKey(String keyName) {
        return PROTECTED_KEYS.contains(keyName);
    }

    private static String normalizeKeyName(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "esc":
                return "escape";
            case "win":
            case "windows":
            case "lwin":
            case "leftwin":
                return "lmeta";
            case "rwin":
            case "rightwin":
                return "rmeta";
            default:
                return normalized;
        }
    }
}
