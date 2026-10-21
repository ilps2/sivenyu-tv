package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 全屏页面共用的音量提示宿主。
 *
 * <p>音量键在部分小米固件上会被系统组件提前消费，应用层收不到按键事件，
 * 所以这里不靠按键驱动，而是由 {@link VolumeMonitor} 轮询系统媒体音量，
 * 数值变化时才弹出提示。音量本身仍由系统调节，应用只读不写。
 *
 * <p>浮层挂在 {@code android.R.id.content} 上，只在 Activity 处于前台时轮询，
 * 切到别的应用立即停止。
 */
abstract class TvActivity extends Activity {
    private VolumeOverlay volumeOverlay;
    private boolean resumed;

    @Override protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        View content = findViewById(android.R.id.content);
        if (content instanceof FrameLayout) {
            volumeOverlay = new VolumeOverlay(this, (FrameLayout) content);
            // 首次启动时 onResume 早于 onPostCreate，这里补一次，
            // 否则浮层建好后不会立刻开始轮询，要等下一次进出页面。
            refreshVolumeFeedback();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshVolumeFeedback();
    }

    final void refreshVolumeFeedback() {
        if (volumeOverlay == null) return;
        volumeOverlay.stop();
        if (resumed) volumeOverlay.start();
    }

    @Override protected void onPause() {
        resumed = false;
        if (volumeOverlay != null) volumeOverlay.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (volumeOverlay != null) volumeOverlay.destroy();
        super.onDestroy();
    }

    static boolean isVolumeKey(KeyEvent event) {
        int code = event.getKeyCode();
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }
}
