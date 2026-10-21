package io.github.op7418.sivenyutv;

import android.service.dreams.DreamService;
import android.view.View;

/**
 * 标准 Android 系统屏保（DreamService）。
 *
 * 注意：本机（MiTV-MFTP0 / Android 9）的固件把 DreamManagerService 整个去掉了，
 * 系统永远不会调用这里。代码保留是为了固件升级或换机后能直接用上——
 * 一旦系统支持，执行下面两行即可接管系统屏保：
 *   adb shell settings put secure screensaver_components io.github.op7418.sivenyutv/.SaverDreamService
 *   adb shell settings put secure screensaver_enabled 1
 */
public final class SaverDreamService extends DreamService {
    private SaverView saver;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        setScreenBright(false);
        setInteractive(false);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        saver = new SaverView(this);
        setContentView(saver);
    }

    @Override public void onDreamingStopped() {
        super.onDreamingStopped();
        if (saver != null) {
            saver.releaseView();
            saver = null;
        }
    }
}
