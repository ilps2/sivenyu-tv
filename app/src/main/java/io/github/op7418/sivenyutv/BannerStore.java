package io.github.op7418.sivenyutv;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.InputStream;

/**
 * 自定义横版图标（banner）覆盖。
 *
 * 查找顺序：
 *   1. U 盘 / 机身存储里的 aptv-banners 目录（任意层级的 /storage 卷）
 *   2. APK 内置 assets/banners/
 * 每处都先按包名找，再按中文名找。文件名支持 png / jpg / jpeg / webp。
 *
 * 这样用户自己生成一张 5:3 的图，拷进 U 盘就能替换，不必重新编译。
 */
final class BannerStore {
    private static final String TAG = "SivenyuBanner";
    private static final String FOLDER = "aptv-banners";
    private static final String[] EXTENSIONS = {"png", "jpg", "jpeg", "webp"};

    private BannerStore() { }

    static android.graphics.drawable.Drawable load(Context context, String pkg, String label) {
        for (File folder : folders()) {
            android.graphics.drawable.Drawable found = fromFolder(folder, pkg, label);
            if (found != null) return found;
        }
        android.graphics.drawable.Drawable asset = fromAssets(context, pkg, label);
        if (asset != null) return asset;
        return null;
    }

    private static File[] folders() {
        java.util.List<File> list = new java.util.ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) {
            list.add(new File(new File(primary, "Download"), FOLDER));
            list.add(new File(primary, FOLDER));
        }
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File volume : volumes) {
                if (volume == null || !volume.isDirectory()) continue;
                String name = volume.getName();
                if ("emulated".equals(name) || "self".equals(name)) continue;
                list.add(new File(volume, FOLDER));
                list.add(new File(new File(volume, "Download"), FOLDER));
            }
        }
        return list.toArray(new File[0]);
    }

    private static android.graphics.drawable.Drawable fromFolder(File folder, String pkg, String label) {
        if (folder == null || !folder.isDirectory()) return null;
        for (String key : new String[]{pkg, label}) {
            if (key == null || key.isEmpty()) continue;
            for (String extension : EXTENSIONS) {
                File file = new File(folder, key + "." + extension);
                if (!file.isFile() || file.length() < 512) continue;
                try {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) {
                        Log.i(TAG, "使用外部 banner " + file.getName());
                        return new android.graphics.drawable.BitmapDrawable(null, bitmap);
                    }
                } catch (RuntimeException | OutOfMemoryError failed) {
                    Log.w(TAG, "读取 banner 失败 " + file.getName(), failed);
                }
            }
        }
        return null;
    }

    private static android.graphics.drawable.Drawable fromAssets(Context context, String pkg, String label) {
        for (String key : new String[]{pkg, label}) {
            if (key == null || key.isEmpty()) continue;
            for (String extension : EXTENSIONS) {
                String path = "banners/" + key + "." + extension;
                try (InputStream stream = context.getAssets().open(path)) {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(stream);
                    if (bitmap != null) {
                        Log.i(TAG, "使用内置 banner " + path);
                        return new android.graphics.drawable.BitmapDrawable(null, bitmap);
                    }
                } catch (java.io.IOException missing) {
                    // 该扩展名不存在，继续试下一个
                } catch (RuntimeException | OutOfMemoryError failed) {
                    Log.w(TAG, "读取内置 banner 失败 " + path, failed);
                }
            }
        }
        return null;
    }
}
