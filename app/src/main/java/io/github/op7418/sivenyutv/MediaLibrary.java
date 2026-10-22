package io.github.op7418.sivenyutv;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 壁纸与屏保共用的媒体目录：{@code files/media/}。
 *
 * <p>这里是唯一的媒体仓库：手机扫码上传、「＋ 本地媒体」导入都落到这个目录，
 * 壁纸库和屏保库都从这一个目录识别内容。图片和视频一视同仁——传一次，
 * 两边都能选，壁纸也能直接播视频。
 *
 * <p>旧版本把图片放在 {@code files/wallpapers/}、视频放在 {@code files/saver/}，
 * {@link #migrate(Context)} 会把它们搬过来（只跑一次，搬完旧目录空着）。
 */
final class MediaLibrary {
    private static final String FOLDER = "media";
    private static final String[] LEGACY_FOLDERS = {"wallpapers", "saver"};

    private MediaLibrary() { }

    /** 统一媒体目录。 */
    static File dir(Context context) {
        File target = new File(context.getFilesDir(), FOLDER);
        if (!target.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
        }
        return target;
    }

    /** 媒体条目：文件名 + 是否视频。 */
    static final class Item {
        final File file;
        final String name;
        final boolean video;

        Item(File file, boolean video) {
            this.file = file;
            this.name = file.getName();
            this.video = video;
        }
    }

    /**
     * 图片扩展名。手机照片与壁纸面板都走这一份，屏保页不再单独写一遍。
     */
    private static final String[] IMAGE_EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".bmp"
    };

    /**
     * 视频扩展名。电视上能解的常见容器都收着；
     * 屏保选择器、上传服务、壁纸库共用这一份，避免三处各写各的。
     */
    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".ts", ".flv", ".wmv", ".3gp"
    };

    static boolean isImage(String name) {
        return endsWithAny(name, IMAGE_EXT);
    }

    static boolean isVideo(String name) {
        return endsWithAny(name, VIDEO_EXT);
    }

    /** 壁纸库和屏保库都认的东西：图片或视频。 */
    static boolean isMedia(String name) {
        return isImage(name) || isVideo(name);
    }

    private static boolean endsWithAny(String name, String[] extensions) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /**
     * 列出目录里的媒体文件，按修改时间从新到旧。
     *
     * <p>壁纸库与屏保库都调这一个方法，保证两边看到的候选完全一致。
     */
    static List<Item> listMedia(File dir) {
        List<Item> items = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return items;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name.startsWith(".")) continue;
            if (!isMedia(name)) continue;
            items.add(new Item(file, isVideo(name)));
        }
        items.sort(Comparator.comparingLong((Item item) -> item.file.lastModified()).reversed());
        return items;
    }

    /**
     * 抽视频的一帧做缩略图。
     *
     * <p>壁纸面板的格子、屏保选择页、裁剪页预览都要给视频一个图，
     * 这里统一用同一个时间点抽帧，避免三处抽到不同的画面。
     *
     * @param targetWidth 目标宽度（高度按比例），0 表示不缩放
     */
    static android.graphics.Bitmap frame(java.io.File file, int targetWidth) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            // 4K 源直接解整帧要十几秒，先按目标宽度解码成小图（API 27+ 才有）
            int width = targetWidth > 0 ? targetWidth : 960;
            int height = 0;
            try {
                String raw = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String rawh = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (raw != null && rawh != null) {
                    int w = Integer.parseInt(raw.trim()), h = Integer.parseInt(rawh.trim());
                    if (w > 0 && h > 0) height = Math.max(1, Math.round(h * width / (float) w));
                }
            } catch (RuntimeException ignored) { }
            if (height <= 0) height = Math.round(width * 9f / 16f);
            android.graphics.Bitmap bitmap = null;
            // 先取第一帧：不需要往后解码，真机上是毫秒级。
            // 之前固定取 2 秒处，4K 视频要把前面所有帧解一遍，实测要 15 秒，
            // 用户进选择页看到的是一格灰块。
            bitmap = grab(retriever, 0L, width, height);
            if (bitmap == null || isBlank(bitmap)) {
                // 片头可能是黑场，这时才值得花时间去取 2 秒处
                android.graphics.Bitmap later = grab(retriever, 2_000_000L, width, height);
                if (later != null) {
                    if (bitmap != null) bitmap.recycle();
                    bitmap = later;
                }
            }
            if (bitmap == null) return null;
            if (targetWidth > 0 && bitmap.getWidth() > targetWidth) {
                int scaledHeight = Math.max(1,
                        Math.round(bitmap.getHeight() * targetWidth / (float) bitmap.getWidth()));
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                        bitmap, targetWidth, scaledHeight, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            return bitmap;
        } catch (Exception | OutOfMemoryError unavailable) {
            android.util.Log.w("TvHome.Media", "视频抽帧失败 " + file.getName(), unavailable);
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * 取指定时间点的一帧；先用缩放到目标尺寸的接口，个别编码器不支持时退回整帧。
     *
     * <p>{@code getScaledFrameAtTime} 是 API 27 才有的，本项目 minSdk 是 26，
     * 必须带版本判断（否则 lint 的 NewApi 检查会让 CI 直接失败）。
     */
    private static android.graphics.Bitmap grab(android.media.MediaMetadataRetriever retriever,
                                                long position, int width, int height) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            try {
                android.graphics.Bitmap scaled = retriever.getScaledFrameAtTime(position,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
                if (scaled != null) return scaled;
            } catch (RuntimeException unavailable) {
                // 继续走整帧回退
            }
        }
        try {
            return retriever.getFrameAtTime(position,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** 近乎纯色（片头黑场、白场）的帧不适合当缩略图。 */
    private static boolean isBlank(android.graphics.Bitmap bitmap) {
        int min = 255, max = 0;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return true;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int pixel = bitmap.getPixel(Math.min(width - 1, width * x / 8),
                        Math.min(height - 1, height * y / 8));
                int luminance = (((pixel >> 16) & 0xFF) * 30 + ((pixel >> 8) & 0xFF) * 59
                        + (pixel & 0xFF) * 11) / 100;
                min = Math.min(min, luminance);
                max = Math.max(max, luminance);
            }
        }
        return max - min < 12;
    }

    /**
     * 读视频的宽高（像素）。取不到时返回 {0, 0}。
     */
    static int[] videoSize(java.io.File file) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String raw = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String rawHeight = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (raw == null || rawHeight == null) return new int[]{0, 0};
            return new int[]{Integer.parseInt(raw), Integer.parseInt(rawHeight)};
        } catch (Exception unavailable) {
            return new int[]{0, 0};
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * 把旧目录里的文件搬进统一目录，并把屏保清单里的旧绝对路径换成新路径。
     * 同分区 renameTo，很快；重复调用无副作用。
     */
    static void migrate(Context context) {
        File media = dir(context);
        File root = context.getFilesDir();
        List<String> moves = new ArrayList<>();
        for (String legacy : LEGACY_FOLDERS) {
            File old = new File(root, legacy);
            File[] files = old.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                File target = unique(media, file.getName());
                if (file.renameTo(target)) {
                    moves.add(file.getAbsolutePath() + "|" + target.getAbsolutePath());
                } else {
                    android.util.Log.w("TvHome.Media", "迁移失败 " + file.getAbsolutePath());
                }
            }
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        if (moves.isEmpty()) return;
        android.util.Log.i("TvHome.Media", "媒体库迁移 " + moves.size() + " 个文件");

        List<String> items = SaverSelection.list(context);
        if (items.isEmpty()) return;
        List<String> updated = new ArrayList<>();
        for (String path : items) {
            String next = path;
            for (String move : moves) {
                int split = move.indexOf('|');
                if (split > 0 && path.equals(move.substring(0, split))) {
                    next = move.substring(split + 1);
                    break;
                }
            }
            updated.add(next);
        }
        SaverSelection.save(context, updated);
    }

    /** 目标已存在时加后缀，避免覆盖同名文件。 */
    static File unique(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 500; i++) {
            File next = new File(dir, base + "-" + i + ext);
            if (!next.exists()) return next;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}
