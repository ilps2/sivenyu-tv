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
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                releasePlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
    }

    View view() {
        return view;
    }

    /** 视频在播时每 400ms 回调一次，用于刷新玻璃折射的取样。 */
    void setTick(Runnable listener) {
        tick = listener;
        if (tick != null) {
            // 自续：每次执行完再排下一次，停止播放时由 stopTick 掐断
            tick = new Runnable() {
                @Override public void run() {
                    if (released || current == null || listener == null) return;
                    listener.run();
                    handler.postDelayed(this, 400L);
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
                prepared.start();
                startTick();
                Log.i(TAG, "视频壁纸开始播放 " + path);
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
        stopTick();
        releasePlayer();
        view.setVisibility(View.GONE);
    }

    /**
     * 视频在播时定期回调，让玻璃折射的取样跟着画面动。
     * 频率不用高：取样本身是缩略图级别的，一秒两三次足够。
     */
    private void startTick() {
        handler.removeCallbacks(tick);
        if (tick == null) return;
        handler.postDelayed(tick, 400L);
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

    private void releasePlayer() {
        MediaPlayer player = this.player;
        this.player = null;
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
        }
        if (surface != null) {
            try { surface.release(); } catch (Exception ignored) { }
            surface = null;
        }
    }
}
