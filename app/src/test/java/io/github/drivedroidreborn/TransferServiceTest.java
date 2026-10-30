package io.github.drivedroidreborn;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class TransferServiceTest {
    private DriveApplication app;

    @Before
    public void before() {
        app = (DriveApplication) RuntimeEnvironment.getApplication();
        TransferService.RUNNING.set(false);
    }

    @After
    public void after() {
        app.worker.shutdownNow();
        app.catalogWorker.shutdownNow();
    }

    @Test
    public void foregroundStartRejectionReleasesBusyStateWithoutCrashing() {
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            TransferService service = controller.get();
            shadowOf(service).setThrowInStartForeground(new SecurityException("quota denied"));
            service.onStartCommand(createIntent(), 0, 1);
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(TransferService.RUNNING.get());
            assertTrue(TransferService.progressText.contains("quota denied"));
            assertTrue(shadowOf(service).isStoppedBySelf());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void queuedCancellationDoesNotCreateOrRegisterImage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        app.worker.submit(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(createIntent(), 0, 1);
            controller.get().onStartCommand(new Intent().setAction(TransferService.CANCEL), 0, 2);
            release.countDown();
            awaitCompletion();
            assertTrue(app.images.all().isEmpty());
            assertEquals(0, app.images.directory().list().length);
            assertTrue(TransferService.progressText.contains("取消"));
        } finally {
            release.countDown();
            controller.destroy();
        }
    }

    @Test
    public void downloadWithoutTrustedChecksumNeverCreatesAFile() throws Exception {
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.DOWNLOAD)
                    .putExtra("url", "https://example.invalid/disk.iso")
                    .putExtra("name", "disk.iso"), 0, 1);
            awaitCompletion();
            assertTrue(app.images.all().isEmpty());
            assertEquals(0, app.images.directory().list().length);
            assertTrue(TransferService.progressText.contains("SHA-256"));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void importWithUnknownSizeCopiesTheExactBytes() throws Exception {
        Uri uri = Uri.parse("content://test.images/disk");
        ShadowContentResolver.registerProviderInternal("test.images", new MetadataProvider("disk.iso", null));
        byte[] contents = new byte[1024 * 1024 + 51];
        new java.util.Random(7).nextBytes(contents);
        shadowOf(app.getContentResolver()).registerInputStream(uri, new ByteArrayInputStream(contents));
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.IMPORT)
                    .putExtra("uri", uri.toString()), 0, 1);
            awaitCompletion();
            assertEquals(1, app.images.all().size());
            File imported = new File(app.images.all().get(0).path);
            assertArrayEquals(contents, Files.readAllBytes(imported.toPath()));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void nullProviderFilenameIsReportedWithoutCreatingAnImage() throws Exception {
        Uri uri = Uri.parse("content://test.images/bad");
        ShadowContentResolver.registerProviderInternal("test.images", new MetadataProvider(null, null));
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.IMPORT)
                    .putExtra("uri", uri.toString()), 0, 1);
            awaitCompletion();
            assertTrue(app.images.all().isEmpty());
            assertTrue(TransferService.progressText.contains("文件名"));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void staleCancelNotificationStopsAnIdleService() {
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.CANCEL), 0, 1);
            assertTrue(shadowOf(controller.get()).isStoppedBySelf());
            assertFalse(TransferService.RUNNING.get());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void compatibilityCopyAddsANewImageAndPreservesOriginal() throws Exception {
        File source = app.images.reserve("original.img");
        byte[] contents = new byte[1024 * 1024 + 13];
        new java.util.Random(42).nextBytes(contents);
        Files.write(source.toPath(), contents);
        app.images.put(new ImageEntry("Original", source.getPath(), contents.length));
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.COPY)
                    .putExtra("path", source.getPath()).putExtra("name", "compatible.img")
                    .putExtra("title", "Compatible"), 0, 1);
            awaitCompletion();
            assertEquals(2, app.images.all().size());
            ImageEntry copy = app.images.all().stream().filter(entry -> entry.title.equals("Compatible")).findFirst().orElseThrow();
            assertNotEquals(source.getPath(), copy.path);
            assertArrayEquals(contents, Files.readAllBytes(source.toPath()));
            assertArrayEquals(contents, Files.readAllBytes(new File(copy.path).toPath()));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void compatibilityCopyRefusesAMountedSource() throws Exception {
        File source = app.images.reserve("busy.img");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        app.getSharedPreferences("usb", android.content.Context.MODE_PRIVATE).edit()
                .putString("session", new org.json.JSONObject().put("image", source.getPath()).toString()).commit();
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.COPY)
                    .putExtra("path", source.getPath()).putExtra("name", "copy.img"), 0, 1);
            awaitCompletion();
            assertTrue(TransferService.progressText.contains("先卸载"));
            assertEquals(1, app.images.directory().list().length);
        } finally {
            controller.destroy();
        }
    }

    @Test
    @Config(shadows = ExportStatShadow.class)
    public void exportingToSourceFileDoesNotTruncateIt() throws Exception {
        File source = app.images.reserve("original.img");
        byte[] contents = new byte[]{7, 8, 9, 10};
        Files.write(source.toPath(), contents);
        Uri target = Uri.parse("content://export.test/same");
        registerExportProvider(source, source);
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.EXPORT)
                    .putExtra("path", source.getPath()).putExtra("uri", target.toString()), 0, 1);
            awaitCompletion();
            assertArrayEquals(contents, Files.readAllBytes(source.toPath()));
            assertTrue(TransferService.progressText, TransferService.progressText.contains("同一个文件"));
        } finally {
            controller.destroy();
        }
    }

    @Test
    @Config(shadows = ExportStatShadow.class)
    public void exportsToDifferentFileWithoutChangingSource() throws Exception {
        File source = app.images.reserve("source.img");
        File destination = app.images.reserve("exported.img");
        byte[] contents = new byte[]{4, 5, 6, 7, 8};
        Files.write(source.toPath(), contents);
        Uri target = Uri.parse("content://export.test/other");
        registerExportProvider(source, destination);
        var controller = Robolectric.buildService(TransferService.class).create();
        try {
            controller.get().onStartCommand(new Intent().setAction(TransferService.EXPORT)
                    .putExtra("path", source.getPath()).putExtra("uri", target.toString()), 0, 1);
            awaitCompletion();
            assertTrue(TransferService.progressText, TransferService.progressText.contains("已导出"));
            assertArrayEquals(contents, Files.readAllBytes(source.toPath()));
            assertArrayEquals(contents, Files.readAllBytes(destination.toPath()));
        } finally {
            controller.destroy();
        }
    }

    private Intent createIntent() {
        return new Intent().setAction(TransferService.CREATE).putExtra("name", "blank.img")
                .putExtra("bytes", 64 * Fat32Image.MIB).putExtra("fat32", true);
    }

    private void registerExportProvider(File source, File target) {
        ExportStatShadow.source = source;
        ExportStatShadow.target = target;
        ExportProvider provider = new ExportProvider(target);
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "export.test";
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
    }

    private void awaitCompletion() throws Exception {
        app.worker.submit(() -> { }).get(8, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(TransferService.RUNNING.get());
    }

    private static class MetadataProvider extends ContentProvider {
        private final String filename;
        private final Long bytes;

        MetadataProvider(String filename, Long bytes) {
            this.filename = filename;
            this.bytes = bytes;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(projection);
            Object[] values = new Object[projection.length];
            for (int index = 0; index < projection.length; index++) {
                values[index] = OpenableColumns.DISPLAY_NAME.equals(projection[index]) ? filename : bytes;
            }
            cursor.addRow(values);
            return cursor;
        }

        @Override
        public String getType(Uri uri) {
            return "application/octet-stream";
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ExportProvider extends MetadataProvider {
        private final File target;

        ExportProvider(File target) {
            super(target.getName(), target.length());
            this.target = target;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            assertEquals("rw", mode);
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE);
            ExportStatShadow.targetDescriptor = descriptor.getFileDescriptor();
            return descriptor;
        }
    }

    /** Robolectric's default fstat reports inode 0 for every descriptor on the PC.
     * Supply file identity for these tests while using real files for byte I/O. */
    @Implements(android.system.Os.class)
    public static class ExportStatShadow {
        static File source;
        static File target;
        static java.io.FileDescriptor targetDescriptor;

        @Implementation
        protected static android.system.StructStat fstat(java.io.FileDescriptor descriptor) {
            boolean isTarget = descriptor == targetDescriptor;
            long inode = isTarget && !source.equals(target) ? 2 : 1;
            return new android.system.StructStat(1, inode, android.system.OsConstants.S_IFREG,
                    1, 0, 0, 0, isTarget ? target.length() : source.length(), 0, 0, 0, 4096, 1);
        }

        @Implementation
        protected static void ftruncate(java.io.FileDescriptor descriptor, long size) throws java.io.IOException {
            assertSame(targetDescriptor, descriptor);
            try (var file = new java.io.RandomAccessFile(target, "rw")) {
                file.setLength(size);
            }
        }

        @Implementation
        protected static void fsync(java.io.FileDescriptor descriptor) {
            assertSame(targetDescriptor, descriptor);
        }
    }
}
