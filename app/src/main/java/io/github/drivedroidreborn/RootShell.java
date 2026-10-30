package io.github.drivedroidreborn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Each request has a bounded lifetime and drains output concurrently. */
public final class RootShell {
    public record Result(int code, String output) {
        public void requireSuccess() throws IOException {
            if (code != 0) {
                throw new IOException(output.trim().isEmpty() ? "Root 命令失败：" + code : output);
            }
        }
    }

    public static String quote(String value) {
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("路径不能包含空字符或换行。");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public Result run(String script) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", "/system/bin/sh")
                    .redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IOException("无法启动 su，请确认已 Root 并允许本应用访问。", exception);
        }
        return runProcess(process, script, 45000);
    }

    static Result runProcess(Process process, String script, long timeoutMillis) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    synchronized (captured) {
                        if (captured.size() < 65536) {
                            captured.write(buffer, 0, Math.min(size, 65536 - captured.size()));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The timeout path closes the process pipes.
            }
        }, "root-output");
        reader.setDaemon(true);
        reader.start();
        Thread writer = new Thread(() -> {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
                input.write('\n');
            } catch (IOException exception) {
                writeFailure.set(exception);
            }
        }, "root-input");
        writer.setDaemon(true);
        writer.start();
        try {
            // Includes waiting for authorization AND writing into su's pipe.
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IOException("Root 操作超时。若 USB 已切换，请使用「弹出 / 恢复 USB」。");
            }
            reader.join(1500);
            writer.join(1500);
            if (writeFailure.get() != null && process.exitValue() == 0) {
                throw new IOException("Root 命令未完整发送，操作没有完成。", writeFailure.get());
            }
            synchronized (captured) {
                return new Result(process.exitValue(), new String(captured.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Root 操作被中断，请检查 USB 状态。", exception);
        } finally {
            process.destroy();
        }
    }
}
