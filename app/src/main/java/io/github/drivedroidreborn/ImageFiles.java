package io.github.drivedroidreborn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

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
        if (!isImage(result)) {
            result += ".img";
        }
        // Android filesystems limit bytes, not Java UTF-16 characters. Leave room
        // for ImageStore's collision prefix and preserve complete code points.
        String extension = result.substring(result.length() - 4);
        String stem = result.substring(0, result.length() - 4);
        StringBuilder shortened = new StringBuilder();
        int byteCount = 0;
        for (int offset = 0; offset < stem.length();) {
            int point = stem.codePointAt(offset);
            String character = new String(Character.toChars(point));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + size > 196) {
                break;
            }
            shortened.append(character);
            byteCount += size;
            offset += Character.charCount(point);
        }
        result = shortened + extension;
        return result;
    }

    public static boolean isImage(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".iso") || lower.endsWith(".img");
    }

    public static long copy(InputStream input, OutputStream output, Progress progress)
            throws IOException {
        return copy(input, output, progress, TransferPolicy.MAX_IMAGE_BYTES);
    }

    static long copy(InputStream input, OutputStream output, Progress progress, long maximumBytes)
            throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must be nonnegative");
        }
        byte[] buffer = new byte[256 * 1024];
        long completed = 0;
        progress.update(0);
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("操作已中断，未完成的文件已保留。");
            }
            if (count > maximumBytes - completed) {
                throw new IOException("数据超过允许的镜像大小，已停止写入。");
            }
            progress.update(completed);
            output.write(buffer, 0, count);
            completed += count;
            progress.update(completed);
        }
        progress.update(completed);
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
