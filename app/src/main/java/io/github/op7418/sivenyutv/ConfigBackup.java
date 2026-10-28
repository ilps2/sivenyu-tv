package io.github.op7418.sivenyutv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配置备份与恢复。
 *
 * <p>壁纸、构图、屏保列表、应用排序这些全散在几个 SharedPreferences 里，
 * 换电视或重装就得重配一遍。这里把它们整体导出成一个 JSON。
 *
 * <p>落在 {@code getExternalFilesDir}，不需要任何存储权限，Android 9 上
 * 文件管理器可以直接访问 {@code /sdcard/Android/data/<包名>/files/}。
 * 开机日志、开机状态这类运行期数据不备份。
 */
final class ConfigBackup {
    /** 需要备份的存储；boot-log / boot-start 属于运行期状态，不带走。 */
    private static final String[] STORES = {
            "frame-wallpapers",
            "aptv-saver",
            "aptv-wallpaper-pool",
            "aptv-wallpaper-crop",
            "aptv-home",
            "volume-feedback"
    };
    private static final String FILE_NAME = "sivenyu-config.json";
    private static final int FORMAT = 1;

    private ConfigBackup() { }

    static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, FILE_NAME);
    }

    /** 导出，返回写入的配置项总数。 */
    static int export(Context context) throws IOException {
        JSONObject root = new JSONObject();
        JSONObject stores = new JSONObject();
        int count = 0;
        try {
            root.put("format", FORMAT);
            root.put("package", context.getPackageName());
            root.put("exportedAt", System.currentTimeMillis());
            for (String name : STORES) {
                SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
                JSONObject store = new JSONObject();
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Set) {
                        JSONArray array = new JSONArray();
                        for (Object item : (Set<?>) value) array.put(String.valueOf(item));
                        store.put(entry.getKey(), array);
                    } else if (value != null) {
                        store.put(entry.getKey(), value);
                    }
                    count++;
                }
                stores.put(name, store);
            }
            root.put("stores", stores);
        } catch (org.json.JSONException impossible) {
            throw new IOException("配置序列化失败", impossible);
        }

        File target = file(context);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        String payload;
        try {
            payload = root.toString(2);
        } catch (org.json.JSONException impossible) {
            throw new IOException("配置序列化失败", impossible);
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return count;
    }

    /** 导入，返回恢复的配置项总数。会把同名存储整体覆盖。 */
    static int restore(Context context) throws IOException {
        File source = file(context);
        if (!source.exists()) throw new IOException("找不到 " + FILE_NAME);
        String raw;
        try (FileInputStream in = new FileInputStream(source)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        int count = 0;
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject stores = root.optJSONObject("stores");
            if (stores == null) throw new IOException("文件格式不对，缺少 stores");
            for (String name : STORES) {
                JSONObject store = stores.optJSONObject(name);
                if (store == null) continue;
                SharedPreferences.Editor editor =
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                editor.clear();
                java.util.Iterator<String> keys = store.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = store.opt(key);
                    if (value instanceof JSONArray) {
                        Set<String> items = new HashSet<>();
                        JSONArray array = (JSONArray) value;
                        for (int i = 0; i < array.length(); i++) items.add(array.optString(i));
                        editor.putStringSet(key, items);
                    } else if (value instanceof Boolean) {
                        editor.putBoolean(key, (Boolean) value);
                    } else if (value instanceof Integer) {
                        editor.putInt(key, (Integer) value);
                    } else if (value instanceof Long) {
                        editor.putLong(key, (Long) value);
                    } else if (value instanceof Double) {
                        editor.putFloat(key, ((Double) value).floatValue());
                    } else if (value != null) {
                        editor.putString(key, String.valueOf(value));
                    }
                    count++;
                }
                editor.apply();
            }
        } catch (org.json.JSONException broken) {
            throw new IOException("配置文件解析失败", broken);
        }
        return count;
    }
}
