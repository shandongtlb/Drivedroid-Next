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
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;

import java.io.File;
import java.io.Closeable;
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
    public static final String COPY = "copy";
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
    private volatile Closeable activeInput;
    private boolean ownsTask;
    private int latestStartId;
    private volatile boolean writingPrivateImage;

    @Override
    public void onCreate() {
        super.onCreate();
        app = (DriveApplication) getApplication();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "镜像操作", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (CANCEL.equals(intent.getAction())) {
            if (ownsTask) {
                cancelTask();
            } else {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (!IMPORT.equals(action) && !EXPORT.equals(action) && !DOWNLOAD.equals(action)
                && !CREATE.equals(action) && !COPY.equals(action)) {
            if (!ownsTask) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }
        ownsTask = true;
        cancelled = false;
        lastProgress = 0;
        writingPrivateImage = !EXPORT.equals(action);
        taskTitle = intent.getStringExtra("title");
        if (taskTitle == null || taskTitle.trim().isEmpty()) {
            taskTitle = "镜像操作";
        }
        progressText = "正在准备：" + taskTitle;
        progressPercent = -1;
        try {
            getSharedPreferences("transfers", MODE_PRIVATE).edit()
                    .putBoolean("interrupted", true).putString("result", progressText).remove("partial").apply();
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
        } catch (RuntimeException exception) {
            finishTask("无法开始文件操作：" + exception.getMessage());
        }
        return START_NOT_STICKY;
    }

    private void perform(Intent intent) {
        File destination = null;
        String result;
        try {
            checkCancelled();
            String action = intent.getAction();
            if (EXPORT.equals(action)) {
                exportImage(intent);
                checkCancelled();
                result = "镜像已导出。";
            } else {
                if (DOWNLOAD.equals(action)) {
                    TransferPolicy.requireChecksum(intent.getStringExtra("sha256"));
                }
                String name = intent.getStringExtra("name");
                if (IMPORT.equals(action)) {
                    Uri uri = requireUri(intent);
                    name = documentName(uri);
                    if (!ImageFiles.isImage(name)) {
                        throw new IOException("请选择 .iso 或 .img 文件；压缩包请先解压。");
                    }
                }
                long expected = -1;
                if (IMPORT.equals(action)) {
                    expected = documentSize(requireUri(intent));
                } else if (COPY.equals(action)) {
                    String sourcePath = intent.getStringExtra("path");
                    if (sourcePath == null || app.usb.isInUse(sourcePath)) {
                        throw new IOException("请先卸载原镜像再创建兼容副本。");
                    }
                    File source = new File(sourcePath);
                    if (!source.isFile() || source.length() == 0) {
                        throw new IOException("无法读取原镜像。");
                    }
                    expected = source.length();
                } else if (CREATE.equals(action)) {
                    expected = intent.getLongExtra("bytes", 0);
                    if (expected < 64 * Fat32Image.MIB || expected > 131072 * Fat32Image.MIB) {
                        throw new IOException("镜像大小必须在 64 MiB 到 128 GiB 之间。");
                    }
                }
                if (expected >= 0) {
                    requireSpace(expected);
                }
                checkCancelled();
                destination = app.images.reserve(name);
                if (!getSharedPreferences("transfers", MODE_PRIVATE).edit()
                        .putString("partial", destination.getAbsolutePath()).commit()) {
                    throw new IOException("无法保存文件操作恢复记录。");
                }
                if (CREATE.equals(action)) {
                    long bytes = intent.getLongExtra("bytes", 0);
                    requireSpace(bytes);
                    updateProgress(0, bytes);
                    Fat32Image.create(destination, bytes, intent.getBooleanExtra("fat32", true),
                            true, completed -> updateProgress(completed, bytes));
                } else if (COPY.equals(action)) {
                    long contentLength = expected;
                    try (InputStream input = new FileInputStream(intent.getStringExtra("path"));
                         FileOutputStream output = new FileOutputStream(destination)) {
                        activeInput = input;
                        long copied = ImageFiles.copy(input, output, completed -> updateProgress(completed, contentLength));
                        output.getFD().sync();
                        verifyLength(contentLength, copied);
                    }
                } else if (IMPORT.equals(action)) {
                    Uri uri = requireUri(intent);
                    long contentLength = expected;
                    try (InputStream input = getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(destination)) {
                        if (input == null) {
                            throw new IOException("无法打开选择的文件。");
                        }
                        activeInput = input;
                        long copied = ImageFiles.copy(input, output, bytes -> updateProgress(bytes, contentLength));
                        output.getFD().sync();
                        verifyLength(contentLength, copied);
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
            result = cancelled ? "操作已取消。" : "操作未完成：" + exception.getMessage();
            if (destination != null) {
                result += "\n未完成的文件已保留，可通过支持 Root 的文件工具手动处理：\n" + destination.getAbsolutePath();
            } else if (EXPORT.equals(intent.getAction())) {
                result += "\n导出位置可能有不完整文件，请手动检查。";
            }
        } finally {
            HttpURLConnection current = connection;
            if (current != null) {
                current.disconnect();
            }
            connection = null;
            activeInput = null;
        }
        finishTask(result);
    }

    private void finishTask(String result) {
        getSharedPreferences("transfers", MODE_PRIVATE).edit()
                .putBoolean("interrupted", false).putString("result", result).apply();
        app.main.post(() -> {
            progressText = result;
            progressPercent = -1;
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(latestStartId);
            ownsTask = false;
            RUNNING.set(false);
            app.changed();
        });
    }

    private void download(Intent intent, File destination) throws Exception {
        String checksum = TransferPolicy.requireChecksum(intent.getStringExtra("sha256"));
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
                activeInput = input;
                long copied = ImageFiles.copy(input, output, bytes -> updateProgress(bytes, expected));
                output.getFD().sync();
                verifyLength(expected, copied);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) {
                actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            if (!checksum.equalsIgnoreCase(actual.toString())) {
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
        long expected = source.length();
        if (!source.isFile() || expected <= 0) {
            throw new IOException("原镜像不存在或为空。");
        }
        // "rw" does not truncate. Compare the actual descriptors before touching
        // the destination, including when a provider points back at the source.
        try (FileInputStream input = new FileInputStream(source);
             ParcelFileDescriptor target = getContentResolver().openFileDescriptor(requireUri(intent), "rw")) {
            if (target == null) {
                throw new IOException("无法打开导出位置。");
            }
            var sourceStat = Os.fstat(input.getFD());
            var targetStat = Os.fstat(target.getFileDescriptor());
            if (sourceStat.st_dev == targetStat.st_dev && sourceStat.st_ino == targetStat.st_ino) {
                throw new IOException("导出位置与原镜像是同一个文件，已取消以保护原文件。");
            }
            checkCancelled();
            boolean regularFile = OsConstants.S_ISREG(targetStat.st_mode);
            if (regularFile) {
                Os.ftruncate(target.getFileDescriptor(), 0);
            }
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
                activeInput = input;
                long copied = ImageFiles.copy(input, output, bytes -> updateProgress(bytes, expected));
                checkCancelled();
                verifyLength(expected, copied);
                if (regularFile) {
                    Os.fsync(target.getFileDescriptor());
                }
            }
        } catch (ErrnoException exception) {
            throw new IOException("无法安全写入导出位置，请改用本地文件目录。", exception);
        }
    }

    private void updateProgress(long bytes, long expected) throws IOException {
        checkCancelled();
        // Called before each write; independent of notification throttling.
        if (writingPrivateImage) {
            requireSpace(256L * 1024);
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastProgress < 500) {
            return;
        }
        lastProgress = now;
        progressText = taskTitle + " · " + ImageFiles.size(bytes)
                + (expected > 0 ? " / " + ImageFiles.size(expected) : "");
        progressPercent = expected > 0 ? (int) Math.min(100, bytes * 100 / expected) : -1;
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(progressText));
        } catch (SecurityException ignored) {
            // Revoking notification permission must not discard a valid transfer.
        }
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
        TransferPolicy.requireSpace(app.images.directory().getUsableSpace(), bytes);
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
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        }
        throw new IllegalArgumentException("文件提供程序没有返回文件名，请换用系统文件应用。");
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
        Closeable input = activeInput;
        Thread cancel = new Thread(() -> {
            if (current != null) {
                current.disconnect();
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // The worker also closes the stream and records cancellation.
                }
            }
        }, "transfer-cancel");
        cancel.setDaemon(true);
        cancel.start();
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
