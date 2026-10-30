package io.github.drivedroidreborn;

import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
