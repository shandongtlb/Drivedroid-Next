package io.github.drivedroidreborn;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class UsbUiConcurrencyTest {
    @Test
    public void readingUiStateDoesNotWaitForRootOperationLock() throws Exception {
        UsbController usb = new UsbController(RuntimeEnvironment.getApplication());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            threads.submit(() -> {
                synchronized (usb) {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNull(threads.submit(usb::session).get(300, TimeUnit.MILLISECONDS));
            assertFalse(threads.submit(() -> usb.isInUse("/images/a.img")).get(300, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }
}
