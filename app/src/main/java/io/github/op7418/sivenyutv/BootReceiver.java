package io.github.op7418.sivenyutv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * 开机拉起桌面。
 *
 * 策略：收到广播后先记一笔自检日志（用于区分"广播没送到"和"拉不起来"），
 * 然后三次尝试拉起：2.5 秒后（等系统就绪）、20 秒后、45 秒后（防被系统桌面顶掉）。
 * 后两次用 AlarmManager 而不是 Handler，因为广播的 goAsync 只有 10 秒窗口。
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SivenyuBoot";
    private static final long FIRST_DELAY_MS = 2500L;
    private static final long[] RETRY_DELAY_MS = {20_000L, 45_000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.mitv.action.BOOT_COMPLETED".equals(action);
        if (!boot) return;

        BootLog.received(context);
        Log.i(TAG, "收到开机广播：" + action);

        if (!BootStart.autoBoot(context)) {
            BootLog.attempt(context, "已关闭自动进入，跳过");
            return;
        }

        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                BootStart.attempt(app, "开机广播");
            } finally {
                pending.finish();
            }
        }, FIRST_DELAY_MS);

        scheduleRetries(app);
    }

    private void scheduleRetries(Context app) {
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < RETRY_DELAY_MS.length; i++) {
            Intent retry = new Intent(app, BootRetryReceiver.class).putExtra("seq", i + 1);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(app, 4100 + i, retry, flags);
            try {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, now + RETRY_DELAY_MS[i], pending);
            } catch (RuntimeException refused) {
                Log.w(TAG, "重试闹钟设置失败 seq=" + (i + 1), refused);
            }
        }
    }
}
