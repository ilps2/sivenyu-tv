package io.github.op7418.sivenyutv;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

/**
 * 壁纸视频播放层。
 *
 * <p>用 {@link TextureView} 而不是 {@code VideoView}：VideoView 底层是 SurfaceView，
 * 它的画面在窗口之下，桌面壁纸栈里（scrim、图标、dock 都在上面）会被挡住；
 * TextureView 是普通 View，能像壁纸图片一样被压在 scrim 下面、被 dock 折射取样。
 *
 * <p>播放规则与屏保视频一致：静音、循环。切换到图片壁纸时立即停掉并隐藏。
 */
final class WallpaperVideo {
    private static final String TAG = "TvHome.Wallpaper";

    private final TextureView view;
    private MediaPlayer player;
    private Surface surface;
    /** SurfaceTexture 还没就绪时，先把要播的路径存着，等回调来了再准备。 */
    private String pending;
    private String current;
    private boolean released;
    private int videoWidth, videoHeight;
    /** 视频在播时定期通知外部刷新玻璃取样。 */
    private Runnable tick;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    WallpaperVideo(Activity activity) {
        view = new TextureView(activity);
        view.setVisibility(View.GONE);
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (pending == null || released) return;
                String path = pending;
                pending = null;
                prepare(path);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                applyScale();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                releasePlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        // 布局变化（窗口尺寸/可见性）后要按新的 view 尺寸重算铺满比例
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyScale());
    }

    /**
     * 铺满裁切。
     *
     * <p>{@code MediaPlayer.setVideoScalingMode} 对 TextureView 不起作用（它只管 SurfaceView 的
     * 显示矩形），TextureView 一律按视频原始宽高比在自身范围内 letterbox，竖屏视频上屏就变成
     * 中间一条、两侧黑边。这里用 {@link TextureView#setTransform} 给内容套一个「短边贴满」的矩阵。
     *
     * <p>不要改成放大 TextureView 自身的尺寸：竖屏视频会把视图撑到 1920×3413，
     * GPU 每帧合成这么大的层会把主线程拖死，遥控器和触摸全部失灵。
     */
    private void applyScale() {
        int vw = videoWidth, vh = videoHeight;
        int width = view.getWidth(), height = view.getHeight();
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return;
        float scale = Math.max(width / (float) vw, height / (float) vh);
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        // 以 view 中心为锚点缩放：多出来的部分上下（或左右）均分裁掉
        matrix.setScale(scale, scale, width / 2f, height / 2f);
        view.setTransform(matrix);
        Log.i(TAG, "视频壁纸铺满 " + vw + "x" + vh + " 视图 " + width + "x" + height
                + " scale=" + scale);
    }

    View view() {
        return view;
    }

    /** 视频在播时每隔一段时间回调一次，用于刷新玻璃折射的取样。 */
    void setTick(Runnable listener) {
        tick = listener;
        if (tick != null) {
            // 自续：每次执行完再排下一次，停止播放时由 stopTick 掐断
            tick = new Runnable() {
                @Override public void run() {
                    if (released || current == null || listener == null) return;
                    listener.run();
                    handler.postDelayed(this, 800L);
                }
            };
        }
    }

    /** 播放一个视频壁纸；同一个文件重复调用等于恢复播放。 */
    void play(String path) {
        if (released || path == null) return;
        if (path.equals(current)) {
            resume();
            return;
        }
        stop();
        current = path;
        // TextureView 在 GONE 状态下不会创建 SurfaceTexture，必须先可见才拿得到
        view.setVisibility(View.VISIBLE);
        // 先挂上待播路径：SurfaceTexture 可能在可见的瞬间就绪，那时回调会直接取走
        pending = path;
        if (view.isAvailable()) {
            pending = null;
            prepare(path);
        }
    }

    private void prepare(String path) {
        releasePlayer();
        try {
            MediaPlayer player = new MediaPlayer();
            this.player = player;
            SurfaceTexture texture = view.getSurfaceTexture();
            if (texture == null) {
                pending = path;
                return;
            }
            surface = new Surface(texture);
            player.setSurface(surface);
            player.setDataSource(path);
            player.setLooping(true);
            // 壁纸是背景，声音只会打扰人
            player.setVolume(0f, 0f);
            // 铺满裁切：视频按短边贴满，多出来的部分裁掉
            player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            player.setOnPreparedListener(prepared -> {
                if (released || player != WallpaperVideo.this.player) return;
                view.setVisibility(View.VISIBLE);
                videoWidth = prepared.getVideoWidth();
                videoHeight = prepared.getVideoHeight();
                // 延后一拍再算：刚 setVisibility(VISIBLE) 时 view 还没测量，尺寸是 0，
                // 立刻算会直接 return，视频就退回 letterbox（竖屏视频两侧黑边）。
                view.post(WallpaperVideo.this::applyScale);
                view.postDelayed(WallpaperVideo.this::applyScale, 250L);
                prepared.start();
                startTick();
                Log.i(TAG, "视频壁纸开始播放 " + path + " " + videoWidth + "x" + videoHeight
                        + " view=" + view.getWidth() + "x" + view.getHeight());
            });
            player.setOnErrorListener((failed, what, extra) -> {
                Log.w(TAG, "视频壁纸播放失败 " + path + " what=" + what + " extra=" + extra);
                view.setVisibility(View.GONE);
                return true;
            });
            player.prepareAsync();
        } catch (Exception | OutOfMemoryError unavailable) {
            Log.w(TAG, "视频壁纸打开失败 " + path, unavailable);
            view.setVisibility(View.GONE);
            releasePlayer();
        }
    }

    /** 停止并隐藏，桌面回到图片壁纸。 */
    void stop() {
        current = null;
        pending = null;
        videoWidth = 0;
        videoHeight = 0;
        stopTick();
        releasePlayer();
        view.setTransform(null);
        view.setVisibility(View.GONE);
    }

    /**
     * 视频在播时定期回调，让玻璃折射的取样跟着画面动。
     * 频率不用高：取样本身是缩略图级别的，一秒两三次足够。
     */
    private void startTick() {
        handler.removeCallbacks(tick);
        if (tick == null) return;
        handler.postDelayed(tick, 800L);
    }

    private void stopTick() {
        handler.removeCallbacks(tick);
    }

    void pause() {
        stopTick();
        if (player == null) return;
        try { player.pause(); } catch (IllegalStateException ignored) { }
    }

    void resume() {
        if (player == null || released) return;
        try { player.start(); } catch (IllegalStateException ignored) { }
        startTick();
    }

    void release() {
        released = true;
        stopTick();
        releasePlayer();
        view.setVisibility(View.GONE);
    }

    /**
     * 释放播放器必须在后台线程做。
     *
     * <p>{@code MediaPlayer.reset()/release()} 在 {@code prepareAsync()} 还没回调时会同步等待，
     * 在电视上能卡住主线程好几秒——表现为「点一下没反应」「壁纸面板点不开」。
     */
    private static final java.util.concurrent.ExecutorService CLEANER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void releasePlayer() {
        final MediaPlayer player = this.player;
        this.player = null;
        // Surface 立刻在主线程放手：TextureView 可能把同一个 SurfaceTexture 复用给下一次播放，
        // 如果拖到后台线程才 release，会把新起的播放器的 surface 一起 detach（-10104）。
        if (surface != null) {
            try { surface.release(); } catch (RuntimeException ignored) { }
            surface = null;
        }
        if (player == null) return;
        try { player.setOnPreparedListener(null); } catch (RuntimeException ignored) { }
        try { player.setOnErrorListener(null); } catch (RuntimeException ignored) { }
        // MediaPlayer.reset()/release() 在 prepareAsync 回调前会同步等待，必须丢到后台
        CLEANER.execute(() -> {
            try { player.reset(); } catch (RuntimeException ignored) { }
            try { player.release(); } catch (RuntimeException ignored) { }
        });
    }
}
