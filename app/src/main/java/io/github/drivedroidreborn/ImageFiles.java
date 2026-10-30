package io.github.drivedroidreborn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class ImageFiles {
    private ImageFiles() {
    }

    public interface Progress {
        void update(long completed) throws IOException;
    }

    public static String safeName(String name) {
        String result = name == null ? "image.img" : name.trim();
        result = result.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (result.isEmpty() || result.equals(".") || result.equals("..")) {
            result = "image.img";
        }
        if (result.length() > 120) {
            result = result.substring(result.length() - 120);
        }
        if (!isImage(result)) {
            result += ".img";
        }
        return result;
    }

    public static boolean isImage(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".iso") || lower.endsWith(".img");
    }

    public static long copy(InputStream input, OutputStream output, Progress progress)
            throws IOException {
        byte[] buffer = new byte[256 * 1024];
        long completed = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("操作已中断，未完成的文件已保留。");
            }
            output.write(buffer, 0, count);
            completed += count;
            progress.update(completed);
        }
        output.flush();
        return completed;
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
