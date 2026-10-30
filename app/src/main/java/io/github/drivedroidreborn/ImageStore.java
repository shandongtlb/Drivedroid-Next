package io.github.drivedroidreborn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ImageStore {
    private final SharedPreferences preferences;
    private final File directory;

    public ImageStore(Context context) {
        preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "images");
    }

    public synchronized List<ImageEntry> all() throws IOException {
        List<ImageEntry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("images", "[]"));
            for (int index = 0; index < array.length(); index++) {
                result.add(ImageEntry.fromJson(array.getJSONObject(index)));
            }
        } catch (JSONException exception) {
            throw new IOException("镜像列表读取失败，原始记录已保留。", exception);
        }
        return result;
    }

    public synchronized void put(ImageEntry entry) throws IOException {
        List<ImageEntry> entries = all();
        entries.removeIf(item -> item.id.equals(entry.id) || item.path.equals(entry.path));
        entries.add(entry);
        save(entries);
    }

    public synchronized void forget(String id) throws IOException {
        List<ImageEntry> entries = all();
        entries.removeIf(item -> item.id.equals(id));
        save(entries);
    }

    private void save(List<ImageEntry> entries) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (ImageEntry entry : entries) {
                array.put(entry.toJson());
            }
        } catch (JSONException exception) {
            throw new IOException(exception);
        }
        if (!preferences.edit().putString("images", array.toString()).commit()) {
            throw new IOException("无法保存镜像列表。");
        }
    }

    public File directory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建镜像目录。");
        }
        return directory;
    }

    public File reserve(String suggestedName) throws IOException {
        String safeName = ImageFiles.safeName(suggestedName);
        File root = directory();
        for (int number = 0; number < 10000; number++) {
            String prefix = number == 0 ? "" : number + "-";
            File candidate = new File(root, prefix + safeName);
            if (candidate.createNewFile()) {
                return candidate;
            }
        }
        throw new IOException("同名文件过多，请换一个文件名。");
    }
}
