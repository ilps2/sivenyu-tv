package io.github.op7418.sivenyutv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One glass lens follows focus; labels never scale or change their metrics. */
final class GlassDock extends FrameLayout {
    private final LinearLayout buttons;
    private final View lens;
    private final Glass glass;
    private final RectF current = new RectF();
    private ValueAnimator motion;
    private final Runnable updateBackdrop, hideIfUnfocused;

    GlassDock(Context context, ImageView wallpaper, View scrim) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        lens = new View(context);
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glass = new Glass(lens, wallpaper, scrim);
        lens.setBackground(glass);
        lens.setAlpha(0f);
        addView(lens, new LayoutParams(-1, -1));
        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(12), dp(10), dp(12), dp(10));
        addView(buttons, new LayoutParams(-2, -2));
        updateBackdrop = glass::refreshBackdrop;
        hideIfUnfocused = () -> {
            if (focusedButton() == null) {
                cancelMotion();
                lens.animate().alpha(0f).setDuration(150).start();
            }
        };
    }

    TextView addItem(String name, Runnable action) {
        TextView item = new TextView(getContext());
        item.setText(name);
        item.setTextSize(17);
        item.setTypeface(Serif.regular(getContext()));
        item.setIncludeFontPadding(false);
        item.setSingleLine(true);
        item.setGravity(Gravity.CENTER);
        item.setTextColor(0xC7FFFFFF);
        item.setShadowLayer(dp(4), 0, dp(1), 0x90000000);
        item.setMinWidth(dp(104));
        item.setMinHeight(dp(48));
        item.setPadding(dp(24), dp(12), dp(24), dp(12));
        item.setFocusable(true);
        item.setId(View.generateViewId());
        item.setOnClickListener(v -> action.run());
        item.setOnFocusChangeListener((v, focused) -> {
            item.setTextColor(focused ? 0xFFFFFFFF : 0xC7FFFFFF);
            if (focused) {
                removeCallbacks(hideIfUnfocused);
                moveTo(item, true);
            } else post(hideIfUnfocused);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = params.rightMargin = dp(6);
        buttons.addView(item, params);
        return item;
    }

    private View focusedButton() {
        for (int i = 0; i < buttons.getChildCount(); i++) {
            View item = buttons.getChildAt(i);
            if (item.isFocused()) return item;
        }
        return null;
    }

    private void moveTo(View item, boolean animate) {
        if (item.getWidth() == 0 || item.getHeight() == 0) return;
        cancelMotion();
        lens.animate().cancel();
        RectF target = new RectF(buttons.getLeft() + item.getLeft(), buttons.getTop() + item.getTop(),
                buttons.getLeft() + item.getRight(), buttons.getTop() + item.getBottom());
        boolean enabled = ValueAnimator.areAnimatorsEnabled();
        if (!animate || !enabled || current.isEmpty() || lens.getAlpha() < 0.01f) {
            current.set(target);
            glass.setFocusRect(current);
            if (animate && enabled) lens.animate().alpha(1f).setDuration(180).start();
            else lens.setAlpha(1f);
            return;
        }
        RectF from = new RectF(current);
        float fromAlpha = lens.getAlpha();
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(260);
        motion.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        motion.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            current.set(from.left + (target.left - from.left) * f,
                    from.top + (target.top - from.top) * f,
                    from.right + (target.right - from.right) * f,
                    from.bottom + (target.bottom - from.bottom) * f);
            glass.setFocusRect(current);
            lens.setAlpha(fromAlpha + (1f - fromAlpha) * f);
        });
        motion.start();
    }

    void refreshBackdrop() {
        removeCallbacks(updateBackdrop);
        post(updateBackdrop);
    }

    /** 视频壁纸的播放层，玻璃在它播放时改取视频当前帧。 */
    void setLiveSource(android.view.TextureView source) {
        glass.setLiveSource(source);
    }

    private void cancelMotion() {
        if (motion != null) motion.cancel();
        motion = null;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // The overlay must not expand a wrap-content dock to the whole TV width.
        measureChild(buttons, widthSpec, heightSpec);
        int width = resolveSize(buttons.getMeasuredWidth(), widthSpec);
        int height = resolveSize(buttons.getMeasuredHeight(), heightSpec);
        setMeasuredDimension(width, height);
        lens.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            View focused = focusedButton();
            if (focused != null) moveTo(focused, false);
            refreshBackdrop();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshBackdrop();
    }

    @Override protected void onDetachedFromWindow() {
        cancelMotion();
        lens.animate().cancel();
        removeCallbacks(updateBackdrop);
        removeCallbacks(hideIfUnfocused);
        glass.releaseBackdrop();
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
