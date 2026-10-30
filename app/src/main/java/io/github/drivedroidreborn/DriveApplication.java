package io.github.drivedroidreborn;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DriveApplication extends Application {
    public static final String ACTION_CHANGED = "io.github.drivedroidreborn.CHANGED";
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    public final Handler main = new Handler(Looper.getMainLooper());
    public ImageStore images;
    public UsbController usb;
    public volatile boolean usbBusy;
    public volatile boolean catalogBusy;
    public volatile java.util.List<ImageCatalog.Distribution> catalog;

    @Override
    public void onCreate() {
        super.onCreate();
        images = new ImageStore(this);
        usb = new UsbController(this);
    }

    public void changed() {
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }
}
