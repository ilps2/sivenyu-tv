package io.github.op7418.sivenyutv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 屏保播放引擎：本地图片与视频混排。
 *
 * 图片：交叉淡入 + 缓慢放大；视频：静音播放、播完自动下一个。
 * 播放列表来自 SaverSelection；未配置时回落到 assets/saver 里的内置图，保证不会黑屏。
 * 本类不处理退出逻辑，由宿主（Activity 或 DreamService）决定。
 */
final class SaverView extends FrameLayout {
    private static final String TAG = "SivenyuSaver";
    private static final long IMAGE_HOLD_MS = 12_000L;
    private static final String[] FALLBACK_ASSETS = {"saver/shadow_empress.jpg", "saver/tidehunter.jpg"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> playlist = new ArrayList<>();

    private ImageView front;
    private ImageView back;
    private VideoView video;
    private TextView clock;

    private int index;
    private boolean released;
    private Bitmap frontBitmap;
    private Bitmap backBitmap;

    private final Runnable advance = () -> play(index + 1);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (clock != null) {
                clock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
                handler.postDelayed(this, 30_000L);
            }
        }
    };

    SaverView(Context context) {
        super(context);
        build();
        load();
        handler.post(tick);
        play(0);
    }

    private void build() {
        // 根容器不画底色：VideoView 的 Surface 位于窗口下方，只有窗口透明处才可见
        View black = new View(getContext());
        black.setBackgroundColor(0xFF000000);
        addView(black, new LayoutParams(-1, -1));

        front = new ImageView(getContext());
        front.setScaleType(ImageView.ScaleType.CENTER_CROP);
        front.setBackgroundColor(0xFF000000);
        addView(front, new LayoutParams(-1, -1));

        back = new ImageView(getContext());
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setAlpha(0f);
        addView(back, new LayoutParams(-1, -1));

        video = new VideoView(getContext());
        // 未播放时不占位：部分电视 GPU 上空的 SurfaceView 会盖住上层视图
        video.setVisibility(GONE);
        addView(video, new LayoutParams(-1, -1));

        clock = new TextView(getContext());
        clock.setTextSize(22);
        clock.setTextColor(0xB3FFFFFF);
        clock.setTypeface(Serif.light(getContext()));
        clock.setShadowLayer(4, 0, 2, 0x99000000);
        LayoutParams clockParams = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        clockParams.rightMargin = dp(56);
        clockParams.bottomMargin = dp(40);
        addView(clock, clockParams);
    }

    private void load() {
        playlist.addAll(SaverSelection.list(getContext()));
        if (!playlist.isEmpty()) return;
        // 未配置时用打包进 APK 的内置壁纸，按文件名顺序轮播
        try {
            String[] files = getContext().getAssets().list("wallpapers/bundled");
            if (files != null) {
                java.util.Arrays.sort(files);
                for (String name : files) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".jpg") || lower.contains("-thumb")) continue;
                    playlist.add("asset:wallpapers/bundled/" + name);
                }
            }
        } catch (java.io.IOException unavailable) {
            Log.w(TAG, "枚举内置壁纸失败", unavailable);
        }
        if (playlist.isEmpty()) {
            for (String asset : FALLBACK_ASSETS) playlist.add("asset:" + asset);
        }
        Log.i(TAG, "未配置屏保内容，使用内置 " + playlist.size() + " 张");
    }

    private void play(int position) {
        if (released || playlist.isEmpty()) return;
        index = position;
        String item = playlist.get(index % playlist.size());
        if (SaverSelection.isVideo(item) && !item.startsWith("asset:")) {
            playVideo(item);
        } else {
            playImage(item);
        }
    }

    private void playImage(String item) {
        handler.removeCallbacks(advance);
        stopVideo();
        Log.i(TAG, "playImage " + item);
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decode(item);
            } catch (Exception | OutOfMemoryError failed) {
                Log.w(TAG, "解码失败 " + item, failed);
            }
            if (bitmap == null) Log.w(TAG, "解码返回空 " + item);
            else Log.i(TAG, "解码成功 " + bitmap.getWidth() + "x" + bitmap.getHeight());
            final Bitmap image = bitmap;
            handler.post(() -> {
                if (released) {
                    if (image != null) image.recycle();
                    return;
                }
                if (image == null) {
                    // 文件缺失或损坏，跳到下一个
                    handler.postDelayed(advance, 400L);
                    return;
                }
                crossFade(image);
                handler.postDelayed(advance, IMAGE_HOLD_MS);
            });
        });
    }

    private void crossFade(Bitmap image) {
        ImageView incoming = front.getAlpha() >= 0.5f ? back : front;
        ImageView outgoing = incoming == front ? back : front;

        Bitmap stale = incoming == front ? frontBitmap : backBitmap;
        incoming.setImageBitmap(image);
        if (incoming == front) frontBitmap = image; else backBitmap = image;
        if (stale != null && stale != image && !stale.isRecycled()) stale.recycle();

        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setAlpha(0f);
        incoming.animate().alpha(1f).scaleX(1.04f).scaleY(1.04f)
                .setDuration(1500L).start();
        outgoing.animate().alpha(0f).setDuration(900L).start();
        Log.i(TAG, "crossFade → " + (incoming == front ? "front" : "back")
                + " 视图尺寸=" + incoming.getWidth() + "x" + incoming.getHeight());
    }

    private void playVideo(String path) {
        handler.removeCallbacks(advance);
        front.animate().alpha(0f).setDuration(400L).start();
        back.animate().alpha(0f).setDuration(400L).start();
        video.setVisibility(VISIBLE);

        video.setOnPreparedListener(player -> {
            player.setVolume(0f, 0f);
            player.setLooping(false);
            video.animate().alpha(1f).setDuration(400L).start();
        });
        video.setOnCompletionListener(player -> {
            if (!released) play(index + 1);
        });
        video.setOnErrorListener((player, what, extra) -> {
            Log.w(TAG, "视频播放失败 " + path + " what=" + what + " extra=" + extra);
            if (!released) handler.postDelayed(advance, 400L);
            return true;
        });
        try {
            video.setAlpha(0f);
            video.setVideoPath(path);
            video.start();
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "视频打开失败 " + path, unavailable);
            handler.postDelayed(advance, 400L);
        }
    }

    private void stopVideo() {
        try {
            video.stopPlayback();
        } catch (RuntimeException ignored) {
            // 未在播放时调用会抛异常，忽略
        }
        video.setVisibility(GONE);
    }

    private Bitmap decode(String item) throws Exception {
        if (item.startsWith("asset:")) {
            return FrameWallpapers.decodeAsset(getContext(), item.substring(6), false);
        }
        java.io.File file = new java.io.File(item);
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(item, bounds);
        if (bounds.outWidth <= 0) return null;
        // 手机竖拍照片是「横着存 + EXIF 90°」，不摆正会横躺
        int rotation = FrameWallpapers.exifRotation(item);
        boolean turned = rotation == 90 || rotation == 270;
        int pictureWidth = turned ? bounds.outHeight : bounds.outWidth;
        int pictureHeight = turned ? bounds.outWidth : bounds.outHeight;
        int limit = pictureHeight > pictureWidth ? 2560 : 1920;
        int longest = Math.max(pictureWidth, pictureHeight);
        int sample = 1;
        while (longest > 0 && longest / sample > limit && sample < 8) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap image = BitmapFactory.decodeFile(item, options);
        if (image == null) return null;
        if (rotation != 0) {
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(rotation);
            Bitmap upright = Bitmap.createBitmap(image, 0, 0,
                    image.getWidth(), image.getHeight(), matrix, true);
            if (upright != image) {
                image.recycle();
                image = upright;
            }
            Log.i(TAG, "EXIF 摆正 " + rotation + "° "
                    + image.getWidth() + "x" + image.getHeight() + " " + file.getName());
        }
        return image;
    }

    /** 宿主销毁时调用，释放解码线程与播放器。 */
    void releaseView() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        stopVideo();
        worker.shutdownNow();
        for (Bitmap bitmap : new Bitmap[]{frontBitmap, backBitmap}) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        frontBitmap = null;
        backBitmap = null;
        front.setImageDrawable(null);
        back.setImageDrawable(null);
    }

    int playlistSize() {
        return playlist.size();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
