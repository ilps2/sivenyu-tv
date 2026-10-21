package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** In-window feedback for TV firmware that hides its volume panel in fullscreen. */
final class VolumeOverlay {
    private static final String TAG = "SivenyuVolume";
    private static final long HIDE_DELAY_MS = 2000L;
    private static final String PREFS = "volume-feedback";

    private final Activity activity;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout parent;
    private final LinearLayout panel;
    private final TextView title;
    private final TextView value;
    private final ProgressBar progress;
    private final VolumeMonitor monitor;
    private boolean readFailed;

    private final Runnable hide = this::hidePanel;

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled).apply();
    }

    VolumeOverlay(Activity activity, FrameLayout parent) {
        this.activity = activity;
        this.parent = parent;
        audio = (AudioManager) activity.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(14));
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        panel.setFocusable(false);
        panel.setClickable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        panel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xED1C2025);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x22FFFFFF);
        panel.setBackground(background);
        panel.setElevation(dp(12));

        LinearLayout labels = new LinearLayout(activity);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        title = label(activity.getString(R.string.volume_label), 15);
        value = label("", 22);
        value.setGravity(Gravity.END);
        labels.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(value, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(labels, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(4));
        bar.topMargin = dp(10);
        panel.addView(progress, bar);

        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(260), -2,
                Gravity.BOTTOM | Gravity.END);
        position.bottomMargin = dp(32);
        position.rightMargin = dp(36);
        panel.setVisibility(View.GONE);
        parent.addView(panel, position);

        monitor = new VolumeMonitor(this::readVolume, new VolumeMonitor.Scheduler() {
            @Override public void postDelayed(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
            }
            @Override public void remove(Runnable task) { handler.removeCallbacks(task); }
        }, this::show);
    }

    void start() {
        if (enabled(activity)) monitor.start();
    }

    void stop() {
        monitor.stop();
        handler.removeCallbacks(hide);
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
    }

    void destroy() {
        stop();
        parent.removeView(panel);
    }

    private void hidePanel() {
        panel.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    private VolumeMonitor.Reading readVolume() {
        if (audio == null) return null;
        try {
            // Read only: the system remains the sole owner of hardware volume adjustment.
            if (audio.isVolumeFixed()) return null;
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            readFailed = false;
            return new VolumeMonitor.Reading(current, maximum, muted);
        } catch (RuntimeException unavailable) {
            if (!readFailed) Log.w(TAG, "无法读取系统媒体音量", unavailable);
            readFailed = true;
            return null;
        }
    }

    private void show(VolumeMonitor.Reading reading) {
        int percent = reading.percent();
        title.setText(reading.muted ? R.string.volume_muted : R.string.volume_label);
        value.setText(activity.getString(R.string.volume_percent, percent));
        progress.setProgress(percent);
        panel.setContentDescription(reading.muted ? activity.getString(R.string.volume_muted)
                : activity.getString(R.string.volume_description, percent));
        panel.animate().cancel();
        panel.setAlpha(1f);
        panel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, HIDE_DELAY_MS);
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(Color.WHITE);
        label.setTypeface(Serif.regular(activity));
        label.setIncludeFontPadding(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
