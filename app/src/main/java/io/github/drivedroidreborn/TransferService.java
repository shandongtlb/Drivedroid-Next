package io.github.drivedroidreborn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.Build;
import android.os.PowerManager;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferService extends Service {
    public static final String IMPORT = "import";
    public static final String CREATE = "create";
    public static final String DOWNLOAD = "download";
    public static final String EXPORT = "export";
    public static final String CANCEL = "cancel";
    public static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    public static volatile String progressText = "";
    public static volatile int progressPercent = -1;
    private static final String CHANNEL = "image-transfers";
    private static final int NOTIFICATION_ID = 10;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private DriveApplication app;
    private PowerManager.WakeLock wakeLock;
    private long lastProgress;
    private String taskTitle;

    @Override
    public void onCreate() {
        super.onCreate();
        app = (DriveApplication) getApplication();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "镜像操作", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (CANCEL.equals(intent.getAction())) {
            cancelTask();
            return START_NOT_STICKY;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }
        cancelled = false;
        taskTitle = intent.getStringExtra("title");
        if (taskTitle == null || taskTitle.trim().isEmpty()) {
            taskTitle = "镜像操作";
        }
        progressText = "正在准备：" + taskTitle;
        progressPercent = -1;
        getSharedPreferences("transfers", MODE_PRIVATE).edit()
                .putBoolean("interrupted", true).putString("result", progressText).apply();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(progressText), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification(progressText));
        }
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "DriveDroidReborn:transfer");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        app.changed();
        app.worker.execute(() -> perform(intent));
        return START_NOT_STICKY;
    }

    private void perform(Intent intent) {
        File destination = null;
        String result;
        try {
            String action = intent.getAction();
            if (EXPORT.equals(action)) {
                exportImage(intent);
                result = "镜像已导出。";
            } else {
                String name = intent.getStringExtra("name");
                if (IMPORT.equals(action)) {
                    Uri uri = requireUri(intent);
                    name = documentName(uri);
                    if (!ImageFiles.isImage(name)) {
                        throw new IOException("请选择 .iso 或 .img 文件；压缩包请先解压。");
                    }
                }
                destination = app.images.reserve(name);
                if (!getSharedPreferences("transfers", MODE_PRIVATE).edit()
                        .putString("partial", destination.getAbsolutePath()).commit()) {
                    throw new IOException("无法保存文件操作恢复记录。");
                }
                if (CREATE.equals(action)) {
                    long bytes = intent.getLongExtra("bytes", 0);
                    requireSpace(bytes);
                    updateProgress(0, bytes);
                    Fat32Image.create(destination, bytes, intent.getBooleanExtra("fat32", true));
                } else if (IMPORT.equals(action)) {
                    Uri uri = requireUri(intent);
                    long expected = documentSize(uri);
                    if (expected > 0) {
                        requireSpace(expected);
                    }
                    try (InputStream input = getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(destination)) {
                        if (input == null) {
                            throw new IOException("无法打开选择的文件。");
                        }
                        long copied = ImageFiles.copy(input, output, bytes -> updateProgress(bytes, expected));
                        output.getFD().sync();
                        verifyLength(expected, copied);
                    }
                } else if (DOWNLOAD.equals(action)) {
                    download(intent, destination);
                } else {
                    throw new IOException("未知镜像操作。");
                }
                checkCancelled();
                if (destination.length() == 0) {
                    throw new IOException("镜像为空。");
                }
                String title = intent.getStringExtra("title");
                if (title == null || title.trim().isEmpty()) {
                    title = destination.getName();
                }
                app.images.put(new ImageEntry(title, destination.getAbsolutePath(), destination.length()));
                result = "已添加：" + title;
                getSharedPreferences("transfers", MODE_PRIVATE).edit().remove("partial").apply();
            }
        } catch (Exception exception) {
            result = "操作未完成：" + exception.getMessage();
            if (destination != null) {
                result += "\n未完成的文件已保留，可通过系统文件工具手动处理：\n" + destination.getAbsolutePath();
            }
        } finally {
            HttpURLConnection current = connection;
            if (current != null) {
                current.disconnect();
            }
            connection = null;
        }
        String finalResult = result;
        getSharedPreferences("transfers", MODE_PRIVATE).edit()
                .putBoolean("interrupted", false).putString("result", result).apply();
        app.main.post(() -> {
            progressText = finalResult;
            progressPercent = -1;
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            RUNNING.set(false);
            app.changed();
        });
    }

    private void download(Intent intent, File destination) throws Exception {
        String checksum = intent.getStringExtra("sha256");
        if (checksum != null && !checksum.trim().isEmpty() && !checksum.matches("(?i)[a-f0-9]{64}")) {
            throw new IOException("SHA-256 必须是 64 位十六进制字符。");
        }
        URL url = new URI(intent.getStringExtra("url")).toURL();
        for (int redirect = 0; redirect <= 5; redirect++) {
            checkCancelled();
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("下载地址和跳转地址必须使用 HTTPS。");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "DriveDroidReborn/0.1");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("服务器跳转缺少目标地址。");
                }
                url = new URL(url, location);
                continue;
            }
            if (status != 200) {
                throw new IOException("下载失败，HTTP " + status);
            }
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                throw new IOException("该地址返回网页，请填写 ISO / IMG 的直接下载链接。");
            }
            long expected = connection.getContentLengthLong();
            if (expected > 0) {
                requireSpace(expected);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(connection.getInputStream(), digest);
                 FileOutputStream output = new FileOutputStream(destination)) {
                long copied = ImageFiles.copy(input, output, bytes -> updateProgress(bytes, expected));
                output.getFD().sync();
                verifyLength(expected, copied);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) {
                actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            if (checksum != null && !checksum.trim().isEmpty() && !checksum.equalsIgnoreCase(actual.toString())) {
                throw new IOException("SHA-256 不匹配，文件没有加入镜像列表。实际值：" + actual);
            }
            return;
        }
        throw new IOException("下载链接跳转次数过多。");
    }

    private void exportImage(Intent intent) throws IOException {
        String path = intent.getStringExtra("path");
        if (path == null || app.usb.isInUse(path)) {
            throw new IOException("请先弹出镜像，再导出文件。");
        }
        File source = new File(path);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(requireUri(intent), "w")) {
            if (output == null) {
                throw new IOException("无法打开导出位置。");
            }
            ImageFiles.copy(input, output, bytes -> updateProgress(bytes, source.length()));
        }
    }

    private void updateProgress(long bytes, long expected) throws IOException {
        checkCancelled();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastProgress < 500) {
            return;
        }
        lastProgress = now;
        progressText = taskTitle + " · " + ImageFiles.size(bytes)
                + (expected > 0 ? " / " + ImageFiles.size(expected) : "");
        progressPercent = expected > 0 ? (int) Math.min(100, bytes * 100 / expected) : -1;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(progressText));
        app.changed();
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, TransferService.class).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("DriveDroid Reborn")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, progressPercent), progressPercent < 0)
                .addAction(new Notification.Action.Builder(null, "取消", cancel).build())
                .build();
    }

    private void checkCancelled() throws IOException {
        if (cancelled) {
            throw new IOException("操作已取消；未完成文件已保留。");
        }
    }

    // Use genuinely free space; do not ask the OS to evict other apps' cached files.
    @android.annotation.SuppressLint("UsableSpace")
    private void requireSpace(long bytes) throws IOException {
        if (bytes > app.images.directory().getUsableSpace() - 64L * 1024 * 1024) {
            throw new IOException("手机剩余空间不足，至少需要额外保留 64 MiB。");
        }
    }

    private void verifyLength(long expected, long actual) throws IOException {
        if (expected >= 0 && expected != actual) {
            throw new IOException("文件长度不符，传输可能中断。");
        }
    }

    private Uri requireUri(Intent intent) throws IOException {
        String value = intent.getStringExtra("uri");
        if (value == null) {
            throw new IOException("没有选择文件。");
        }
        Uri uri = Uri.parse(value);
        if (!"content".equals(uri.getScheme())) {
            throw new IOException("请选择系统文件管理器提供的文件。");
        }
        return uri;
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return "image.img";
    }

    private long documentSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return -1;
    }

    private void cancelTask() {
        cancelled = true;
        HttpURLConnection current = connection;
        if (current != null) {
            current.disconnect();
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        cancelTask();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        cancelTask();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
