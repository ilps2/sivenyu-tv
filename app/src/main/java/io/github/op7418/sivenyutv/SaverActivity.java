package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** 全屏屏保宿主：显示图片/视频混排，任意键或触摸退出。 */
public final class SaverActivity extends Activity {
    private static final String TAG = "SivenyuSaver";

    /**
     * 启动后的冷却窗口：从桌面或设置里点进屏保时，那一次确认键的按下/抬起
     * 会落在新启动的 Activity 上，没有冷却就会被当成「任意键」立刻关掉，
     * 表现正是「点屏保一闪就回到桌面」。
     */
    private static final long GRACE_MS = 1600;

    private SaverView saver;
    private long startedAt;
    private boolean dismissed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startedAt = SystemClock.uptimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        saver = new SaverView(this);
        setContentView(saver);
        Log.i(TAG, "屏保启动，清单 " + SaverSelection.list(this).size() + " 项");
    }

    private boolean inGrace() {
        return SystemClock.uptimeMillis() - startedAt < GRACE_MS;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (inGrace()) return true;   // 冷却期内一律吞掉，避免被刚才那次按键打掉
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.i(TAG, "屏保退出：按键 code=" + event.getKeyCode());
            dismiss();
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (inGrace()) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            Log.i(TAG, "屏保退出：触摸");
            dismiss();
        }
        return true;
    }

    private void dismiss() {
        if (dismissed) return;
        dismissed = true;
        finish();
    }

    @Override protected void onDestroy() {
        Log.i(TAG, "屏保结束，存活 " + ((SystemClock.uptimeMillis() - startedAt) / 1000) + " 秒");
        if (saver != null) {
            saver.releaseView();
            saver = null;
        }
        super.onDestroy();
    }
}
