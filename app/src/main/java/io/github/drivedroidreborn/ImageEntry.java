package io.github.drivedroidreborn;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;

public final class ImageEntry {
    public final String id;
    public final String title;
    public final String path;
    public final long bytes;

    public ImageEntry(String title, String path, long bytes) {
        this(UUID.randomUUID().toString(), title, path, bytes);
    }

    private ImageEntry(String id, String title, String path, long bytes) {
        this.id = id;
        this.title = title;
        this.path = path;
        this.bytes = bytes;
    }

    public String filename() {
        return new File(path).getName();
    }

    public ImageEntry renamed(String value) {
        return new ImageEntry(id, value, path, bytes);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("path", path)
                .put("bytes", bytes);
    }

    public static ImageEntry fromJson(JSONObject value) throws JSONException {
        return new ImageEntry(value.getString("id"), value.getString("title"),
                value.getString("path"), value.getLong("bytes"));
    }
}
