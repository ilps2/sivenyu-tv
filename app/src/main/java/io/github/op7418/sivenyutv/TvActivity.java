package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/** Shares foreground-only volume feedback across the app's fullscreen pages. */
abstract class TvActivity extends Activity {
    private VolumeOverlay volumeOverlay;
    private boolean resumed;

    @Override protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        View content = findViewById(android.R.id.content);
        if (content instanceof FrameLayout) {
            volumeOverlay = new VolumeOverlay(this, (FrameLayout) content);
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
