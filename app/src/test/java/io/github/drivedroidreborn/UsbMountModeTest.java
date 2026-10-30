package io.github.drivedroidreborn;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class UsbMountModeTest {
    @Test
    public void modeLabelRequiresActualKernelFlags() {
        assertEquals("可写 USB（内核已确认）", UsbController.actualMode("0", "0"));
        assertEquals("只读 USB", UsbController.actualMode("1", "0"));
        assertEquals("只读 CD-ROM", UsbController.actualMode("1", "1"));
        assertEquals("状态未知", UsbController.actualMode("", "0"));
        assertEquals("状态未知", UsbController.actualMode("0", ""));
        assertEquals("状态未知", UsbController.actualMode("0", "1"));
    }

    @Test
    public void staleImageRowCannotEjectAnotherImage() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var original = new org.json.JSONObject().put("image", "/images/other.img");
        context.getSharedPreferences("usb", android.content.Context.MODE_PRIVATE).edit()
                .putString("session", original.toString()).commit();
        UsbController usb = new UsbController(context);
        var error = assertThrows(java.io.IOException.class, () -> usb.eject("/images/old.img"));
        assertTrue(error.getMessage().contains("已经改变"));
        assertTrue(usb.isInUse("/images/other.img"));
    }
}
