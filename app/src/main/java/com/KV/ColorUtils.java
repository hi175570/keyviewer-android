package com.KV;

public class ColorUtils {
    
    /**
     * HEX 字符串转 ARGB 整数
     * 支持格式：#RRGGBB 或 #AARRGGBB
     */
    public static int hexToInt(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFF999999;
        String clean = hex.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (clean.length() == 6) clean = "FF" + clean;
        if (clean.length() != 8) return 0xFF999999;
        try {
            return (int) Long.parseLong(clean, 16);
        } catch (NumberFormatException e) {
            return 0xFF999999;
        }
    }
    
    /**
     * ARGB 整数转 HEX 字符串
     * 输出格式：#AARRGGBB
     */
    public static String intToHex(int color) {
        return String.format("#%08X", color);
    }
    
    /**
     * 提取 Alpha 通道
     */
    public static int getAlpha(int color) {
        return (color >> 24) & 0xFF;
    }
    
    /**
     * 提取 Red 通道
     */
    public static int getRed(int color) {
        return (color >> 16) & 0xFF;
    }
    
    /**
     * 提取 Green 通道
     */
    public static int getGreen(int color) {
        return (color >> 8) & 0xFF;
    }
    
    /**
     * 提取 Blue 通道
     */
    public static int getBlue(int color) {
        return color & 0xFF;
    }
    
    /**
     * 组合 ARGB
     */
    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
    
    /**
     * RGB 转 HSV（用于颜色选择器）
     */
    public static float[] rgbToHsv(int red, int green, int blue) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(red, green, blue, hsv);
        return hsv;
    }
    
    /**
     * HSV 转 RGB int
     */
    public static int hsvToRgb(float hue, float saturation, float value) {
        return android.graphics.Color.HSVToColor(new float[]{hue, saturation, value});
    }
}