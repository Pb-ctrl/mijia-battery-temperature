package cn.local.mijiabattery;

import android.content.ContentResolver;
import android.os.SystemClock;
import android.provider.Settings;

final class Sample {
    static final String KEY = "mijia_bt_sample";
    final String epoch;
    final long timestamp;
    final int temperature;
    final String source;

    private Sample(String epoch, long timestamp, int temperature, String source) {
        this.epoch = epoch;
        this.timestamp = timestamp;
        this.temperature = temperature;
        this.source = source;
    }

    boolean isFresh() {
        long age = SystemClock.elapsedRealtime() / 1000 - timestamp;
        return age >= 0 && age < 45;
    }

    static Sample read(ContentResolver resolver) {
        String value = Settings.Global.getString(resolver, KEY);
        if (value == null) return null;
        String[] fields = value.split("\\|", -1);
        if (fields.length != 4 || fields[0].isEmpty() || fields[3].isEmpty()) return null;
        try {
            Sample sample = new Sample(fields[0], Long.parseLong(fields[1]),
                    Integer.parseInt(fields[2]), fields[3]);
            return sample.temperature >= -400 && sample.temperature <= 1200
                    && sample.isFresh() ? sample : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
