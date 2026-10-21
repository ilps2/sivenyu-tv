package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;

/** 屏幕保护（待机轮播壁纸）设置。分钟数 0 表示关闭。 */
final class ScreenSaver {
    private static final String PREFS = "aptv-home";
    private static final String KEY_MINUTES = "screensaver-minutes";

    static final int[] MINUTES = {0, 1, 3, 5, 10, 15};
    static final String[] LABELS = {"关闭", "1 分钟", "3 分钟", "5 分钟", "10 分钟", "15 分钟"};

    private ScreenSaver() { }

    static int minutes(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_MINUTES, 3);
    }

    static void setMinutes(Context c, int value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MINUTES, value).apply();
    }

    static String label(Context c) {
        int minutes = minutes(c);
        return minutes <= 0 ? "已关闭" : minutes + " 分钟无操作后轮播";
    }
}
