package cn.local.mijiabattery;

import java.util.Locale;

public final class Logic {
    public static final String PREFIX = "b477e7e0-";
    public static final int OFFSET = 1000;
    private Logic() {}
    public static boolean marked(String id) { return id != null && id.startsWith(PREFIX); }
    public static int encode(int degrees) {
        if (degrees < 1 || degrees > 80) throw new IllegalArgumentException("temperature range");
        return OFFSET + degrees;
    }
    public static boolean valid(int[] v) {
        return v != null && v.length == 3 && v[0] >= 0 && v[0] <= 2
            && v[1] > OFFSET && v[1] <= OFFSET + 80 && v[2] == v[1];
    }
    public static boolean satisfied(int[] v, int tenths) {
        if (!valid(v) || tenths < -400 || tenths > 1200) return false;
        int threshold = (v[1] - OFFSET) * 10;
        return v[0] == 0 ? tenths < threshold : v[0] == 1 ? tenths > threshold : true;
    }
    public static boolean crossing(int[] v, int before, int now) {
        if (!valid(v) || before == Integer.MIN_VALUE || now == before) return false;
        if (v[0] == 2) return true;
        return !satisfied(v, before) && satisfied(v, now);
    }
    public static boolean shouldTrigger(int[] v, Integer before, int now) {
        return before == null ? satisfied(v, now) : crossing(v, before, now);
    }
    public static String summary(int[] v) {
        if (!valid(v)) return "温度条件无效";
        return v[0] == 2 ? "电池温度发生变化时" : "电池温度" + (v[0] == 0 ? "低于 " : "高于 ") + (v[1] - OFFSET) + "℃";
    }
    public static String display(int tenths) { return String.format(Locale.ROOT, "%.1f℃", tenths / 10.0); }
}
