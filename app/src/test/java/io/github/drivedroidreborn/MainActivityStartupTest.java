package io.github.drivedroidreborn;

import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.app.AlertDialog;
import android.widget.PopupMenu;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

/** Runs Android lifecycle and view code on the PC, without ADB or a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class MainActivityStartupTest {
    @Test
    public void freshInstallReachesVisibleImageListWithoutRoot() {
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            View root = layout(activity);
            assertNotNull(findText(root, "当前没有挂载镜像"));
            assertNotNull(findText(root, "还没有镜像"));
        }
    }

    @Test
    public void settingsAndHelpCanOpenAndReturnToImageList() {
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            View root = layout(activity);
            assertTrue(findDescription(root, "设置").performClick());
            assertNotNull(findText(layout(activity), "USB 设置向导"));
            assertTrue(findDescription(layout(activity), "返回镜像列表").performClick());
            assertTrue(findDescription(layout(activity), "帮助").performClick());
            assertNotNull(findText(layout(activity), "把手机作为启动盘"));
            assertTrue(findDescription(layout(activity), "返回镜像列表").performClick());
            assertNotNull(findText(layout(activity), "还没有镜像"));
        }
    }

    @Test
    public void activityCanRecreateAndReturnFromBackground() {
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            controller.setup().visible();
            controller.recreate();
            assertNotNull(findText(layout(controller.get()), "当前没有挂载镜像"));
            controller.pause().stop().start().resume().visible();
            assertNotNull(findText(layout(controller.get()), "还没有镜像"));
        }
    }

    @Test
    public void restoredPagesCreateTheirWindowBeforeAccessingSystemBars() {
        String[][] pages = {
                {"settings", "USB 设置向导"},
                {"downloads", "镜像仓库"},
                {"help", "把手机作为启动盘"}
        };
        for (String[] page : pages) {
            Bundle savedState = new Bundle();
            savedState.putString("screen", page[0]);
            try (var controller = Robolectric.buildActivity(MainActivity.class)) {
                MainActivity activity = controller.create(savedState).start().resume().visible().get();
                assertNotNull(findText(layout(activity), page[1]));
            }
        }
    }

    @Test
    public void createDialogPreservesDraftAfterRecreation() {
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            openAddMenu(activity, 2);
            AlertDialog oldDialog = ShadowAlertDialog.getLatestAlertDialog();
            EditText filename = (EditText) findDescription(oldDialog.getWindow().getDecorView(), "文件名");
            filename.setText("我的镜像.img");
            controller.recreate();
            layout(controller.get());
            AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
            assertFalse(oldDialog.isShowing());
            assertTrue(restored.isShowing());
            assertNotNull(findText(restored.getWindow().getDecorView(), "我的镜像.img"));
        }
    }

    @Test
    public void invalidImageCapacityKeepsDialogOpenWithoutStartingService() {
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            openAddMenu(activity, 2);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            EditText capacity = (EditText) findDescription(dialog.getWindow().getDecorView(), "大小（MiB）");
            capacity.setText("0");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(capacity.getError());
            assertFalse(TransferService.RUNNING.get());
        }
    }

    @Test
    public void downloadCannotStartWithoutPublisherChecksumAndHasNoDefaultRepository() {
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            openAddMenu(activity, 3);
            assertNotNull(findText(layout(activity), "自定义源 · 未配置"));
            View entry = (View) findText(layout(activity), "输入下载地址").getParent();
            entry.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View root = dialog.getWindow().getDecorView();
            ((EditText) findDescription(root, "https://example.org/system.iso"))
                    .setText("https://example.org/image.iso");
            ((EditText) findDescription(root, "保存文件名，如 system.iso")).setText("image.iso");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            EditText checksum = (EditText) findDescription(root, "发布方 SHA-256（必填）");
            assertNotNull(checksum.getError());
            assertFalse(TransferService.RUNNING.get());
        }
    }

    @Test
    public void mountedImageHasItsOwnEjectButtonAndCancelKeepsFileRecord() throws Exception {
        DriveApplication app = (DriveApplication) org.robolectric.RuntimeEnvironment.getApplication();
        ImageEntry active = new ImageEntry("Active disk", "/images/active.img", 1024);
        ImageEntry other = new ImageEntry("Other disk", "/images/other.img", 2048);
        app.images.put(active);
        app.images.put(other);
        app.getSharedPreferences("usb", android.content.Context.MODE_PRIVATE).edit()
                .putString("session", new org.json.JSONObject().put("image", active.path)
                        .put("title", active.title).put("mode", "可写 USB").toString()).commit();
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            View root = layout(activity);
            View button = findDescription(root, "卸载镜像：Active disk");
            assertNotNull(button);
            assertNull(findDescription(root, "卸载镜像：Other disk"));
            button.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("卸载镜像", shadowOf(dialog).getTitle());
            assertTrue(shadowOf(dialog).getMessage().toString().contains("Active disk"));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(app.usb.isInUse(active.path));
            assertEquals(2, app.images.all().size());
        }
    }

    @Test
    public void tappingMountedImageOffersEjectInsteadOfAnotherMount() throws Exception {
        DriveApplication app = (DriveApplication) org.robolectric.RuntimeEnvironment.getApplication();
        ImageEntry entry = new ImageEntry("Mounted disk", "/images/mounted.img", 1024);
        app.images.put(entry);
        app.getSharedPreferences("usb", android.content.Context.MODE_PRIVATE).edit()
                .putString("session", new org.json.JSONObject().put("image", entry.path).toString()).commit();
        try (var controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().visible().get();
            View row = (View) findText(layout(activity), entry.title).getParent().getParent();
            row.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("卸载 / 弹出镜像", shadowOf(dialog).getItems()[0]);
        }
    }

    private static void openAddMenu(MainActivity activity, int itemId) {
        findDescription(layout(activity), "添加镜像").performClick();
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        shadowOf(popup).getOnMenuItemClickListener().onMenuItemClick(popup.getMenu().findItem(itemId));
        popup.dismiss();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static View layout(MainActivity activity) {
        shadowOf(Looper.getMainLooper()).idle();
        View root = activity.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
        return root;
    }

    private static View findDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findDescription(group.getChildAt(index), description);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TextView findText(View view, String value) {
        if (view instanceof TextView text && text.getText().toString().equals(value)) {
            return text;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), value);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
