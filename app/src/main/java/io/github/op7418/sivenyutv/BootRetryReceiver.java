package io.github.op7418.sivenyutv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机拉起的后续重试。
 *
 * 部分小米/红米固件会在开机广播之后再把原桌面拉到前台，
 * 我们第一次拉起会被盖住，所以由 AlarmManager 在 20 秒、45 秒再试一次。
 */
public final class BootRetryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int seq = intent == null ? 0 : intent.getIntExtra("seq", 0);
        Log.i("SivenyuBoot", "开机重试 seq=" + seq);
        BootStart.attempt(context, "开机重试 " + seq);
    }
}
