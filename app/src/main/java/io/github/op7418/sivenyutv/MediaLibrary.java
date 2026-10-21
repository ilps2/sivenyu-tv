package io.github.op7418.sivenyutv;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 壁纸与屏保共用的媒体目录：{@code files/media/}。
 *
 * <p>图片既能当壁纸，也能进屏保轮播；视频只进屏保。手机扫码上传、「＋ 本地图片」导入
 * 都落到这里，所以传一次两边都能用。
 *
 * <p>旧版本把图片放在 {@code files/wallpapers/}、视频放在 {@code files/saver/}，
 * {@link #migrate(Context)} 会把它们搬过来（只跑一次，搬完旧目录空着）。
 */
final class MediaLibrary {
    private static final String FOLDER = "media";
    private static final String[] LEGACY_FOLDERS = {"wallpapers", "saver"};

    private MediaLibrary() { }

    static File dir(Context context) {
        File target = new File(context.getFilesDir(), FOLDER);
        if (!target.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
        }
        return target;
    }

    static boolean isImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
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

    private static File unique(File dir, String name) {
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
