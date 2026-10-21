package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 记录开机广播与拉起尝试，供自检页判断问题卡在哪一环。 */
final class BootLog {
    private static final String PREFS = "boot-log";

    private BootLog() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    static void received(Context c) {
        prefs(c).edit()
                .putLong("last-boot-at", System.currentTimeMillis())
                .putInt("last-boot-count", bootCount(c))
                .putInt("attempts", 0)
                .putString("last-result", "等待拉起")
                .apply();
    }

    static void attempt(Context c, String result) {
        prefs(c).edit()
                .putInt("attempts", prefs(c).getInt("attempts", 0) + 1)
                .putLong("last-attempt-at", System.currentTimeMillis())
                .putString("last-result", result)
                .apply();
    }

    static long lastBootAt(Context c) {
        return prefs(c).getLong("last-boot-at", 0L);
    }

    static int attempts(Context c) {
        return prefs(c).getInt("attempts", 0);
    }

    static String lastResult(Context c) {
        return prefs(c).getString("last-result", "");
    }

    static long lastAttemptAt(Context c) {
        return prefs(c).getLong("last-attempt-at", 0L);
    }

    static String describeBoot(Context c) {
        long at = lastBootAt(c);
        if (at <= 0L) return "从未收到开机广播";
        return "收到过 · " + format(at) + " · 第 " + prefs(c).getInt("last-boot-count", -1) + " 次开机";
    }

    static String describeAttempt(Context c) {
        String result = lastResult(c);
        if (result.isEmpty()) return "无记录";
        long at = lastAttemptAt(c);
        return result + (at > 0 ? " · " + format(at) : "");
    }

    private static String format(long at) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(at));
    }
}
