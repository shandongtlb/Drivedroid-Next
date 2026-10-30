package io.github.drivedroidreborn;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BLUE = 0xff039be5;
    private static final int INK = 0xff303030;
    private static final int MUTED = 0xff757575;
    private static final int PAPER = 0xfffafafa;
    private static final int IMPORT_REQUEST = 10;
    private static final int EXPORT_REQUEST = 11;
    private DriveApplication app;
    private SharedPreferences preferences;
    private LinearLayout body;
    private FrameLayout stage;
    private String screen = "images";
    private String exportPath;
    private String exportName;
    private int scrollPosition;
    private ScrollView listScroll;
    private Bundle drafts = new Bundle();
    private String activeForm = "";
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (screen.equals("images")) {
                rememberScroll();
                showImages();
            } else if (screen.equals("downloads")) {
                showDownloads();
            }
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (DriveApplication) getApplication();
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        if (state != null) {
            screen = state.getString("screen", "images");
            exportPath = state.getString("exportPath");
            exportName = state.getString("exportName");
            scrollPosition = state.getInt("scrollPosition", 0);
            activeForm = state.getString("activeForm", "");
            Bundle savedDrafts = state.getBundle("drafts");
            if (savedDrafts != null) {
                drafts = savedDrafts;
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
        }
        showCurrentScreen();
        switch (activeForm) {
            case "create" -> createImage();
            case "path" -> addPath();
            case "download" -> downloadDialog();
            case "repository" -> repositoryDialog();
            default -> {
            }
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // The legacy overload only runs before API 33.
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DriveApplication.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(changes, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(changes, filter);
        }
        if (screen.equals("images")) {
            showImages();
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(changes);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        rememberScroll();
        state.putString("screen", screen);
        state.putString("exportPath", exportPath);
        state.putString("exportName", exportName);
        state.putInt("scrollPosition", scrollPosition);
        state.putString("activeForm", activeForm);
        state.putBundle("drafts", new Bundle(drafts));
        super.onSaveInstanceState(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation") // API 33+ uses OnBackInvokedDispatcher in onCreate.
    public void onBackPressed() {
        goBack();
    }

    private void goBack() {
        if (screen.equals("images")) {
            finish();
        } else {
            showImages();
        }
    }

    private void rememberScroll() {
        if (screen.equals("images") && listScroll != null) {
            scrollPosition = listScroll.getScrollY();
        }
    }

    private void showCurrentScreen() {
        switch (screen) {
            case "settings" -> showSettings();
            case "downloads" -> showDownloads();
            case "help" -> showHelp();
            default -> showImages();
        }
    }

    private void shell(String page, String title) {
        screen = page;
        LinearLayout root = column();
        root.setBackgroundColor(0xff0277bd);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                var keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
        } else {
            getWindow().setStatusBarColor(0xff0277bd);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
        LinearLayout toolbar = row();
        toolbar.setBackgroundColor(BLUE);
        toolbar.setElevation(dp(3));
        if (!page.equals("images")) {
            toolbar.addView(iconButton("back", "返回镜像列表", Color.WHITE, view -> showImages()));
        }
        TextView heading = text(title, 20, Color.WHITE);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(dp(page.equals("images") ? 16 : 4), 0, 0, 0);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -1, 1));
        if (page.equals("images")) {
            toolbar.addView(iconButton("refresh", "刷新并检测 USB", Color.WHITE,
                    view -> runUsb("正在检测 USB…", () -> app.usb.inspect(), null)));
            toolbar.addView(iconButton("settings", "设置", Color.WHITE, view -> {
                rememberScroll();
                showSettings();
            }));
            toolbar.addView(iconButton("help", "帮助", Color.WHITE, view -> showHelp()));
        }
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        stage = new FrameLayout(this);
        stage.setBackgroundColor(PAPER);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column();
        stage.addView(body, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // PhoneWindow.getInsetsController() dereferences its DecorView. The decor
        // is created by setContentView(), so accessing it earlier crashes on launch.
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
    }

    private void showImages() {
        shell("images", "DriveDroid");
        JSONObject session;
        List<ImageEntry> entries;
        try {
            session = app.usb.session();
            entries = new ArrayList<>(app.images.all());
        } catch (IOException exception) {
            error(exception);
            return;
        }
        if (preferences.getBoolean("sort", true)) {
            entries.sort(Comparator.comparing(entry -> entry.title.toLowerCase(Locale.ROOT)));
        }
        LinearLayout status = row();
        status.setPadding(dp(14), 0, dp(12), 0);
        status.setBackground(ripple(0xffeeeeee));
        status.addView(new IconView(this, "usb", MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
        String statusLabel = session == null ? "当前没有挂载镜像" :
                (app.usb.verified() ? "正在挂载 " : "挂载记录 · 待检测 ") + session.optString("title");
        if (app.usbBusy) {
            statusLabel = "正在处理 USB，请稍候…";
        }
        TextView state = text(statusLabel, 14, INK);
        state.setSingleLine(true);
        state.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        state.setPadding(dp(14), 0, dp(8), 0);
        status.addView(state, new LinearLayout.LayoutParams(0, -1, 1));
        status.addView(new IconView(this, "arrow", MUTED), new LinearLayout.LayoutParams(dp(22), dp(22)));
        status.setOnClickListener(view -> showHosting());
        body.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));
        divider(body);

        if (TransferService.RUNNING.get()) {
            TextView progress = text(TransferService.progressText + "  ·  点击取消", 13, 0xff0277bd);
            progress.setPadding(dp(16), dp(8), dp(16), dp(8));
            progress.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("取消当前操作？")
                    .setMessage("未完成的文件会保留，不会自动删除。")
                    .setNegativeButton("继续操作", null)
                    .setPositiveButton("取消操作", (dialog, which) -> startService(
                            new Intent(this, TransferService.class).setAction(TransferService.CANCEL))).show());
            body.addView(progress);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(TransferService.progressPercent < 0);
            bar.setProgress(TransferService.progressPercent);
            body.addView(bar, new LinearLayout.LayoutParams(-1, dp(4)));
        } else if (!TransferService.progressText.isEmpty()) {
            TextView completed = text(TransferService.progressText, 13, MUTED);
            completed.setMaxLines(2);
            completed.setEllipsize(android.text.TextUtils.TruncateAt.END);
            completed.setPadding(dp(16), dp(8), dp(16), dp(8));
            completed.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("文件操作结果")
                    .setMessage(TransferService.progressText).setPositiveButton("关闭", null)
                    .setNeutralButton("收起提示", (dialog, which) -> {
                        TransferService.progressText = "";
                        showImages();
                    }).show());
            body.addView(completed);
        }

        listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        LinearLayout list = column();
        list.setPadding(0, 0, 0, dp(96));
        listScroll.addView(list);
        body.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (entries.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(80), dp(28), dp(20));
            empty.addView(new IconView(this, "usb", 0xffbdbdbd), new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView label = text("还没有镜像", 18, MUTED);
            label.setPadding(0, dp(22), 0, dp(8));
            empty.addView(label);
            TextView hint = text("点击右下角 ＋ 添加 ISO / IMG\n或创建一个空白 USB 磁盘", 14, MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(5), 1);
            empty.addView(hint);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        } else {
            for (ImageEntry entry : entries) {
                boolean mounted = session != null && entry.path.equals(session.optString("image"));
                addImageRow(list, entry, mounted);
            }
        }
        listScroll.post(() -> listScroll.scrollTo(0, scrollPosition));
        FrameLayout add = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xffef3e33);
        add.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff), circle, null));
        add.setElevation(dp(6));
        IconView plus = new IconView(this, "plus", Color.WHITE);
        FrameLayout.LayoutParams plusLayout = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER);
        add.addView(plus, plusLayout);
        add.setContentDescription("添加镜像");
        add.setFocusable(true);
        add.setOnClickListener(this::showAddMenu);
        FrameLayout.LayoutParams addLayout = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        addLayout.setMargins(dp(24), dp(24), dp(20), dp(20));
        stage.addView(add, addLayout);
    }

    private void addImageRow(LinearLayout list, ImageEntry entry, boolean mounted) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(76));
        line.setPadding(dp(16), dp(10), dp(12), dp(10));
        line.setBackground(ripple(mounted ? 0xffeaf6fc : Color.WHITE));
        String icon = entry.filename().toLowerCase(Locale.ROOT).endsWith(".iso") ? "disc" : "usb";
        line.addView(new IconView(this, icon, mounted ? BLUE : MUTED), new LinearLayout.LayoutParams(dp(28), dp(32)));
        LinearLayout labels = column();
        labels.setPadding(dp(16), 0, dp(8), 0);
        TextView title = text(entry.title, 16, INK);
        title.setMaxLines(2);
        TextView subtitle = text(entry.filename(), 13, MUTED);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        subtitle.setPadding(0, dp(5), 0, 0);
        labels.addView(title);
        labels.addView(subtitle);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView size = text((mounted ? "已选择\n" : "") + ImageFiles.size(entry.bytes), 12, mounted ? BLUE : MUTED);
        size.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        line.addView(size);
        line.setOnClickListener(view -> showMount(entry));
        line.setOnLongClickListener(view -> {
            imageActions(entry);
            return true;
        });
        list.addView(line);
        divider(list);
    }

    private void showAddMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 0, 0, "从文件导入镜像");
        popup.getMenu().add(0, 1, 1, "添加已有镜像路径");
        popup.getMenu().add(0, 2, 2, "创建空白镜像");
        popup.getMenu().add(0, 3, 3, "下载镜像");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0 -> importImage();
                case 1 -> addPath();
                case 2 -> createImage();
                default -> showDownloads();
            }
            return true;
        });
        popup.show();
    }

    private void showMount(ImageEntry entry) {
        if (busy()) {
            return;
        }
        String[] items = {"只读 USB（推荐）", "可写 USB", "只读 CD-ROM", "镜像详情与管理"};
        new AlertDialog.Builder(this).setTitle("挂载镜像为…")
                .setItems(items, (dialog, which) -> {
                    if (which == 3) {
                        imageActions(entry);
                        return;
                    }
                    UsbController.Mode mode = UsbController.Mode.values()[which];
                    String message = entry.title + "\n\n" + mode.label
                            + "模式会暂时替换手机的 USB 连接，MTP 和有线 ADB 会断开。弹出后恢复。";
                    if (mode == UsbController.Mode.WRITABLE) {
                        message += "\n\n电脑可以修改此镜像。使用完请先在电脑安全弹出，且不要同时在手机编辑此文件。";
                    }
                    new AlertDialog.Builder(this).setTitle("挂载镜像")
                            .setMessage(message).setNegativeButton("取消", null)
                            .setPositiveButton("挂载", (confirmation, choice) -> runUsb("正在挂载…",
                                    () -> app.usb.mount(entry, mode, preferences.getString("backend", "auto")),
                                    "挂载命令已完成，请在电脑检查 USB 设备。")).show();
                }).show();
    }

    private void showHosting() {
        try {
            JSONObject state = app.usb.session();
            if (state == null) {
                new AlertDialog.Builder(this).setTitle("USB 状态")
                        .setMessage("当前没有挂载镜像。首次使用请检测 Root 与 USB 接口。")
                        .setNegativeButton("关闭", null)
                        .setPositiveButton("检测 USB", (dialog, which) -> detectUsb()).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle(state.optString("title"))
                    .setMessage(state.optString("mode") + "\n" + state.optString("image")
                            + "\n\n请先在电脑安全弹出，再恢复手机 USB。若上次挂载失败，也可使用此操作恢复。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("检查状态", (dialog, which) -> detectUsb())
                    .setPositiveButton("弹出 / 恢复 USB", (dialog, which) -> runUsb("正在恢复 USB…",
                            () -> app.usb.eject(), "USB 恢复完成。")).show();
        } catch (IOException exception) {
            error(exception);
        }
    }

    private void importImage() {
        if (busy()) {
            return;
        }
        new AlertDialog.Builder(this).setTitle("导入镜像")
                .setMessage("选择 ISO / IMG 后，会复制到应用镜像目录。大文件也可以使用「添加已有镜像路径」，避免占用双份空间。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择文件", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, IMPORT_REQUEST);
                }).show();
    }

    private void addPath() {
        LinearLayout form = form();
        EditText title = field(form, "显示名称（可选）", InputType.TYPE_CLASS_TEXT);
        EditText path = field(form, "/storage/emulated/0/Download/example.iso", InputType.TYPE_CLASS_TEXT);
        trackField(title, "path_title", "");
        trackField(path, "path_value", "");
        form.addView(note("通过 Root 验证并使用原文件，不复制。若内核拒绝共享存储路径，请改用文件导入。"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加已有镜像路径").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        trackDialog(dialog, "path");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = path.getText().toString().trim();
            try {
                UsbController.validateImagePath(value);
            } catch (IOException exception) {
                path.setError(exception.getMessage());
                return;
            }
            String name = title.getText().toString().trim();
            dialog.dismiss();
            runUsb("正在验证镜像…", () -> {
                long bytes = app.usb.fileSize(value);
                app.images.put(new ImageEntry(name.isEmpty() ? new java.io.File(value).getName() : name, value, bytes));
            }, "镜像已添加。");
        }));
        dialog.show();
    }

    private void createImage() {
        if (busy()) {
            return;
        }
        LinearLayout form = form();
        EditText name = field(form, "文件名", InputType.TYPE_CLASS_TEXT);
        name.setText(R.string.default_image_name);
        EditText size = field(form, "大小（MiB）", InputType.TYPE_CLASS_NUMBER);
        size.setText(R.string.default_image_size);
        trackField(name, "create_name", "disk.img");
        trackField(size, "create_size", "1024");
        CheckBox fat = new CheckBox(this);
        fat.setText(R.string.format_fat32);
        fat.setChecked(drafts.getBoolean("create_fat32", true));
        fat.setOnCheckedChangeListener((button, checked) -> drafts.putBoolean("create_fat32", checked));
        form.addView(fat);
        form.addView(note("64–131072 MiB。FAT32 单个文件最大 4 GiB。取消勾选可生成未格式化的镜像，供 Rufus 等工具写入。空白镜像本身不可引导。"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("创建空白镜像").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("创建", null).create();
        trackDialog(dialog, "create");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            long mib;
            try {
                mib = Long.parseLong(size.getText().toString());
                if (mib < 64 || mib > 131072) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                size.setError("请输入 64–131072 之间的整数");
                return;
            }
            String filename = ImageFiles.safeName(name.getText().toString());
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".img")) {
                name.setError("空白磁盘文件名应以 .img 结尾");
                return;
            }
            dialog.dismiss();
            beginTransfer(new Intent(this, TransferService.class).setAction(TransferService.CREATE)
                    .putExtra("name", filename).putExtra("title", filename)
                    .putExtra("bytes", mib * Fat32Image.MIB).putExtra("fat32", fat.isChecked()));
        }));
        dialog.show();
    }

    private void imageActions(ImageEntry entry) {
        new AlertDialog.Builder(this).setTitle(entry.title)
                .setItems(new String[]{"查看路径与大小", "修改显示名称", "导出镜像", "从列表移除（保留文件）"}, (dialog, which) -> {
                    switch (which) {
                        case 0 -> new AlertDialog.Builder(this).setTitle(entry.title)
                                .setMessage(entry.path + "\n\n" + ImageFiles.size(entry.bytes))
                                .setPositiveButton("关闭", null).setNeutralButton("复制路径", (d, w) -> copy(entry.path)).show();
                        case 1 -> rename(entry);
                        case 2 -> export(entry);
                        default -> forget(entry);
                    }
                }).show();
    }

    private void rename(ImageEntry entry) {
        LinearLayout form = form();
        EditText value = field(form, "显示名称", InputType.TYPE_CLASS_TEXT);
        value.setText(entry.title);
        new AlertDialog.Builder(this).setTitle("修改显示名称").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    String title = value.getText().toString().trim();
                    if (!title.isEmpty()) {
                        localWork(() -> app.images.put(entry.renamed(title)));
                    }
                }).show();
    }

    private void forget(ImageEntry entry) {
        new AlertDialog.Builder(this).setTitle("从列表移除？")
                .setMessage("只移除「" + entry.title + "」的列表记录，镜像文件会保留。")
                .setNegativeButton("取消", null).setPositiveButton("移除记录", (dialog, which) -> localWork(() -> {
                    if (app.usb.isInUse(entry.path)) {
                        throw new IOException("请先弹出镜像。");
                    }
                    app.images.forget(entry.id);
                })).show();
    }

    private void export(ImageEntry entry) {
        if (busy()) {
            return;
        }
        try {
            if (app.usb.isInUse(entry.path)) {
                throw new IOException("请先弹出镜像再导出。");
            }
        } catch (IOException exception) {
            error(exception);
            return;
        }
        exportPath = entry.path;
        exportName = entry.filename();
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, exportName), EXPORT_REQUEST);
    }

    private void showSettings() {
        shell("settings", "设置");
        LinearLayout list = scrollingBody();
        section(list, "USB");
        setting(list, "USB 设置向导", "检查 Root、控制器和内核接口", this::detectUsb);
        String backend = preferences.getString("backend", "auto");
        setting(list, "USB 系统", backend.equals("auto") ? "自动（优先 ConfigFS）" : backend,
                () -> new AlertDialog.Builder(this).setTitle("USB 系统")
                        .setSingleChoiceItems(new String[]{"自动（推荐）", "ConfigFS", "传统 Android USB"},
                                backend.equals("auto") ? 0 : backend.equals("configfs") ? 1 : 2,
                                (dialog, which) -> {
                                    preferences.edit().putString("backend", new String[]{"auto", "configfs", "legacy"}[which]).apply();
                                    dialog.dismiss();
                                    showSettings();
                                }).setNegativeButton("取消", null).show());
        setting(list, "弹出 / 恢复 USB", "恢复本应用切换前的 USB 连接", this::showHosting);
        setting(list, "检测报告", "查看或复制最近一次检测结果", this::diagnostics);
        section(list, "镜像");
        setting(list, "按名称排序", preferences.getBoolean("sort", true) ? "已开启" : "按添加顺序",
                () -> {
                    preferences.edit().putBoolean("sort", !preferences.getBoolean("sort", true)).apply();
                    showSettings();
                });
        setting(list, "镜像目录", getFilesDir() + "/images", () -> copy(getFilesDir() + "/images"));
        setting(list, "镜像下载仓库", preferences.getString("repository", "https://dd.hexed.pw/repo.json"), this::repositoryDialog);
        setting(list, "最近的文件操作", "查看完成结果、失败原因或中断记录", () -> {
            SharedPreferences transfers = getSharedPreferences("transfers", MODE_PRIVATE);
            String message = transfers.getString("result", "尚无文件操作。");
            if (!TransferService.RUNNING.get() && transfers.getBoolean("interrupted", false)) {
                message = "上次操作被系统中断，文件没有加入列表。\n\n" + message
                        + "\n" + transfers.getString("partial", "");
            }
            new AlertDialog.Builder(this).setTitle("文件操作结果").setMessage(message).setPositiveButton("关闭", null).show();
        });
        section(list, "关于");
        setting(list, "DriveDroid Reborn 0.1.1", "独立实现 · Android 8–17 · 目标 API 37", this::showHelp);
    }

    private void detectUsb() {
        runUsb("正在检测 Root 和 USB…", () -> app.usb.inspect(), null, this::diagnostics);
    }

    private void diagnostics() {
        TextView report = text(app.usb.lastDiagnostics(), 13, INK);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        report.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        new AlertDialog.Builder(this).setTitle("USB 检测报告").setView(scroll)
                .setNegativeButton("关闭", null).setNeutralButton("复制", (dialog, which) -> copy(report.getText().toString()))
                .setPositiveButton("重新检测", (dialog, which) -> detectUsb()).show();
    }

    private void showDownloads() {
        shell("downloads", "下载镜像");
        LinearLayout list = scrollingBody();
        section(list, "镜像仓库");
        setting(list, "加载 / 刷新发行版列表", app.catalogBusy ? "正在获取仓库…" :
                "社区源 · " + preferences.getString("repository", "https://dd.hexed.pw/repo.json"), this::loadCatalog);
        setting(list, "更换仓库", "支持 DriveDroid 格式的 HTTPS JSON 仓库", this::repositoryDialog);
        if (app.catalog != null) {
            section(list, "可下载的发行版（" + app.catalog.size() + "）");
            for (ImageCatalog.Distribution distro : app.catalog) {
                setting(list, distro.name(), distro.releases().size() + " 个 ISO / IMG 镜像",
                        () -> selectRelease(distro));
            }
        }
        section(list, "从直接链接下载");
        setting(list, "输入下载地址", "HTTPS · 后台下载 · 可选 SHA-256 校验", this::downloadDialog);
        section(list, "发行版官网");
        setting(list, "Ubuntu", "选择 Desktop ISO，再复制直接下载链接", () -> browse("https://ubuntu.com/download/desktop"));
        setting(list, "Debian", "安装镜像与校验文件", () -> browse("https://www.debian.org/distrib/"));
        setting(list, "Arch Linux", "镜像站列表与 SHA-256", () -> browse("https://archlinux.org/download/"));
        setting(list, "Fedora", "Workstation Live ISO", () -> browse("https://fedoraproject.org/workstation/download"));
        setting(list, "GParted Live", "磁盘分区与维护", () -> browse("https://gparted.org/download.php"));
        list.addView(note("官网入口会在浏览器打开。下载后可以导入，也可以复制最终 ISO / IMG 链接到本应用。此版本未内置会随时间失效的固定版本下载地址。"));
    }

    private void downloadDialog() {
        if (busy()) {
            return;
        }
        LinearLayout form = form();
        EditText url = field(form, "https://example.org/system.iso", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText filename = field(form, "保存文件名，如 system.iso", InputType.TYPE_CLASS_TEXT);
        EditText sha = field(form, "SHA-256（可选）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        trackField(url, "download_url", "");
        trackField(filename, "download_filename", "");
        trackField(sha, "download_sha", "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("下载镜像").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("下载", null).create();
        trackDialog(dialog, "download");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = url.getText().toString().trim();
            Uri parsed = Uri.parse(address);
            if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null) {
                url.setError("请输入有效的 HTTPS 直接下载地址");
                return;
            }
            String name = filename.getText().toString().trim();
            if (name.isEmpty()) {
                name = parsed.getLastPathSegment();
            }
            if (name == null || !ImageFiles.isImage(name)) {
                filename.setError("文件名应以 .iso 或 .img 结尾");
                return;
            }
            String hash = sha.getText().toString().trim();
            if (!hash.isEmpty() && !hash.matches("(?i)[a-f0-9]{64}")) {
                sha.setError("SHA-256 应为 64 位十六进制字符");
                return;
            }
            dialog.dismiss();
            beginTransfer(new Intent(this, TransferService.class).setAction(TransferService.DOWNLOAD)
                    .putExtra("name", name).putExtra("title", name).putExtra("url", address).putExtra("sha256", hash));
        }));
        dialog.show();
    }

    private void repositoryDialog() {
        LinearLayout form = form();
        EditText url = field(form, "HTTPS JSON 仓库地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        trackField(url, "repository_url", preferences.getString("repository", "https://dd.hexed.pw/repo.json"));
        form.addView(note("默认源由社区维护，与本应用及原作者无隶属关系。只显示 HTTPS 的未压缩 ISO / IMG；下载前会显示实际文件地址。"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("镜像下载仓库").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        trackDialog(dialog, "repository");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = url.getText().toString().trim();
            Uri parsed = Uri.parse(address);
            if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null) {
                url.setError("请输入有效的 HTTPS 仓库地址");
                return;
            }
            if (app.catalogBusy) {
                toast("请等待仓库加载完成。");
                return;
            }
            preferences.edit().putString("repository", address).apply();
            app.catalog = null;
            dialog.dismiss();
            showDownloads();
        }));
        dialog.show();
    }

    private void loadCatalog() {
        if (busy() || app.catalogBusy) {
            return;
        }
        String address = preferences.getString("repository", "https://dd.hexed.pw/repo.json");
        app.catalogBusy = true;
        showDownloads();
        app.catalogWorker.execute(() -> {
            Exception failure = null;
            try {
                app.catalog = ImageCatalog.fetch(address);
            } catch (Exception exception) {
                failure = exception;
            }
            app.catalogBusy = false;
            app.changed();
            Exception finalFailure = failure;
            app.main.post(() -> {
                if (finalFailure != null) {
                    error(finalFailure);
                } else if (app.catalog != null && app.catalog.isEmpty()) {
                    toast("仓库中没有可用的 HTTPS ISO / IMG 链接。");
                }
            });
        });
    }

    private void selectRelease(ImageCatalog.Distribution distro) {
        String[] labels = new String[distro.releases().size()];
        for (int index = 0; index < labels.length; index++) {
            ImageCatalog.Release release = distro.releases().get(index);
            labels[index] = release.name() + "\n" + release.filename() + " · " + ImageFiles.size(release.bytes());
        }
        new AlertDialog.Builder(this).setTitle(distro.name()).setItems(labels, (dialog, which) -> {
            ImageCatalog.Release release = distro.releases().get(which);
            new AlertDialog.Builder(this).setTitle("下载这个镜像？")
                    .setMessage(release.name() + "\n" + ImageFiles.size(release.bytes()) + "\n\n" + release.url()
                            + (release.sha256().isEmpty() ? "\n\n仓库未提供 SHA-256；可在直接链接下载里手动填写。" : "\n\n将校验仓库提供的 SHA-256。"))
                    .setNegativeButton("取消", null).setPositiveButton("下载", (confirmation, choice) ->
                            beginTransfer(new Intent(this, TransferService.class).setAction(TransferService.DOWNLOAD)
                                    .putExtra("name", release.filename()).putExtra("title", release.name())
                                    .putExtra("url", release.url()).putExtra("sha256", release.sha256()))).show();
        }).setNegativeButton("返回", null).show();
    }

    private void showHelp() {
        shell("help", "帮助与使用说明");
        LinearLayout list = scrollingBody();
        section(list, "把手机作为启动盘");
        paragraph(list, "1. 检测 USB", "在设置里运行 USB 设置向导，并在 Root 管理器授权。支持与否取决于内核驱动和系统 USB 服务，检测完成后仍需实际挂载验证。");
        paragraph(list, "2. 添加镜像", "导入 ISO / IMG，或填写已有文件的绝对路径。长按列表项可改名、导出或移除记录。应用私有目录中的镜像会随卸载应用丢失，请提前导出。");
        paragraph(list, "3. 连接电脑并挂载", "使用能传输数据的 USB 线直连电脑，选择只读 USB、可写 USB 或 CD-ROM。切换时 MTP 和有线 ADB 会断开，随后在电脑启动菜单选择 USB 设备。");
        paragraph(list, "4. 安全弹出", "先在电脑安全弹出，再点顶部状态栏的「弹出 / 恢复 USB」。只在电脑端弹出不一定恢复手机的 USB 配置。");
        section(list, "兼容性");
        paragraph(list, "Android 17 / OPPO", "按 Android 17（API 37）构建，未对你的 OPPO Find X9 Ultra 真机调试。若内核没有 USB Mass Storage，或 ColorOS USB 服务持续接管，应用不能单凭 Root 保证成功。不会自动改 SELinux、停止系统服务或刷内核。");
        paragraph(list, "ISO 与启动方式", "Linux hybrid ISO 通常可使用 USB 模式。部分 Windows ISO 需要 CD-ROM 模式，或先创建空白磁盘后由电脑的 Rufus 写入。Secure Boot 和 UEFI 支持取决于镜像本身。");
        paragraph(list, "文件操作", "文件导入与下载在前台服务中执行。取消或中断后保留未完成文件；不自动删除镜像。移除列表记录不释放文件空间。FAT32 单文件上限为 4 GiB 减 1 字节。");
        section(list, "关于这个版本");
        paragraph(list, "DriveDroid Reborn 0.1.1", "参考 DriveDroid 0.10.50 的界面布局独立实现，非原作者发行版，不包含原版应用源码、图标或 APK。当前提供单镜像挂载。");
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (request == IMPORT_REQUEST) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (SecurityException ignored) {
            // Some providers only supply a temporary grant for this activity session.
        }
        if (request == IMPORT_REQUEST) {
            beginTransfer(new Intent(this, TransferService.class).setAction(TransferService.IMPORT)
                    .putExtra("uri", uri.toString()));
        } else if (request == EXPORT_REQUEST && exportPath != null) {
            beginTransfer(new Intent(this, TransferService.class).setAction(TransferService.EXPORT)
                    .putExtra("uri", uri.toString()).putExtra("path", exportPath).putExtra("title", exportName));
        }
    }

    private void beginTransfer(Intent intent) {
        if (busy()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        }
        try {
            startForegroundService(intent);
            showImages();
        } catch (RuntimeException exception) {
            error(exception);
        }
    }

    private boolean busy() {
        if (app.usbBusy || TransferService.RUNNING.get()) {
            toast("请等待当前操作完成。");
            return true;
        }
        return false;
    }

    private interface Work {
        void run() throws Exception;
    }

    private void runUsb(String status, Work work, String success) {
        runUsb(status, work, success, null);
    }

    private void runUsb(String status, Work work, String success, Runnable after) {
        if (busy()) {
            return;
        }
        app.usbBusy = true;
        toast(status);
        app.changed();
        app.worker.execute(() -> {
            Exception failure = null;
            try {
                work.run();
            } catch (Exception exception) {
                failure = exception;
            }
            app.usbBusy = false;
            app.changed();
            Exception finalFailure = failure;
            app.main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (finalFailure != null) {
                    error(finalFailure);
                } else {
                    if (success != null) {
                        toast(success);
                    }
                    if (after != null) {
                        after.run();
                    }
                }
            });
        });
    }

    private void localWork(Work work) {
        if (busy()) {
            return;
        }
        app.worker.execute(() -> {
            try {
                work.run();
                app.changed();
            } catch (Exception exception) {
                app.main.post(() -> error(exception));
            }
        });
    }

    private void error(Exception exception) {
        if (!isFinishing() && !isDestroyed()) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message)
                    .setPositiveButton("关闭", null).setNeutralButton("复制详情", (dialog, which) -> copy(message)).show();
        }
    }

    private void copy(String content) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("DriveDroid", content));
        toast("已复制。");
    }

    private void browse(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (android.content.ActivityNotFoundException exception) {
            error(new IOException("没有可打开链接的浏览器。"));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout form() {
        LinearLayout result = column();
        result.setPadding(dp(20), dp(4), dp(20), dp(8));
        return result;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private EditText field(LinearLayout form, String hint, int type) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setTextSize(15);
        field.setInputType(type);
        field.setSingleLine(true);
        form.addView(field, new LinearLayout.LayoutParams(-1, dp(56)));
        return field;
    }

    private void trackField(EditText field, String key, String initial) {
        field.setText(drafts.getString(key, initial));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                drafts.putString(key, value.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable value) {
            }
        });
    }

    private void trackDialog(AlertDialog dialog, String form) {
        activeForm = form;
        dialog.setOnDismissListener(ignored -> {
            if (!isChangingConfigurations()) {
                activeForm = "";
            }
        });
    }

    private TextView note(String value) {
        TextView note = text(value, 13, MUTED);
        note.setPadding(dp(16), dp(16), dp(16), dp(16));
        note.setLineSpacing(dp(4), 1);
        return note;
    }

    private FrameLayout iconButton(String icon, String description, int color, View.OnClickListener listener) {
        FrameLayout button = new FrameLayout(this);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -1));
        button.setBackground(ripple(Color.TRANSPARENT));
        button.setContentDescription(description);
        button.setFocusable(true);
        button.addView(new IconView(this, icon, color), new FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER));
        button.setOnClickListener(listener);
        return button;
    }

    private RippleDrawable ripple(int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x20000000),
                new android.graphics.drawable.ColorDrawable(color), null);
    }

    private void divider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0xffdedede);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private LinearLayout scrollingBody() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(0, 0, 0, dp(24));
        scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return list;
    }

    private void section(LinearLayout list, String title) {
        TextView label = text(title, 14, 0xff0277bd);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(dp(16), dp(22), dp(16), dp(10));
        list.addView(label);
    }

    private void setting(LinearLayout list, String title, String subtitle, Runnable action) {
        LinearLayout item = column();
        item.setPadding(dp(16), dp(14), dp(16), dp(14));
        item.setMinimumHeight(dp(72));
        item.setBackground(ripple(Color.WHITE));
        item.addView(text(title, 16, INK));
        TextView details = text(subtitle, 13, MUTED);
        details.setPadding(0, dp(5), 0, 0);
        item.addView(details);
        item.setOnClickListener(view -> action.run());
        list.addView(item);
        divider(list);
    }

    private void paragraph(LinearLayout list, String title, String content) {
        TextView heading = text(title, 16, INK);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(16), dp(12), dp(16), 0);
        list.addView(heading);
        list.addView(note(content));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
