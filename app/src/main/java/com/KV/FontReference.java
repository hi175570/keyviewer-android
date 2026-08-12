package com.KV;

/** Stable font identifiers stored in KV configs without embedding platform fonts. */
final class FontReference {
    static final String MAPLESTORY = "builtin:maplestory";
    static final String ADOFAI = "builtin:adofai";
    static final String SANS = "builtin:sans";
    static final String SERIF = "builtin:serif";
    static final String MONOSPACE = "builtin:monospace";
    static final String SYSTEM_PREFIX = "system:";

    private FontReference() {}

    static boolean isLogical(String value) {
        if (value == null) return false;
        return value.startsWith("builtin:") || value.startsWith(SYSTEM_PREFIX);
    }

    static String displayName(String value) {
        if (value == null || value.isEmpty() || MAPLESTORY.equals(value)) {
            return "Maplestory";
        }
        if (ADOFAI.equals(value)) return "ADOFAI";
        if (SANS.equals(value)) return "系统无衬线";
        if (SERIF.equals(value)) return "系统衬线";
        if (MONOSPACE.equals(value)) return "系统等宽";
        if (value.startsWith(SYSTEM_PREFIX)) return "Windows 字体（安卓回退默认）";
        return "已导入字体文件";
    }
}
