package io.github.drivedroidreborn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public final class UsbController {
    public enum Mode {
        READ_ONLY("只读 USB", "1", "0"),
        WRITABLE("可写 USB", "0", "0"),
        CD_ROM("只读 CD-ROM", "1", "1");

        public final String label;
        final String readonly;
        final String cdrom;

        Mode(String label, String readonly, String cdrom) {
            this.label = label;
            this.readonly = readonly;
            this.cdrom = cdrom;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final RootShell root = new RootShell();
    private volatile boolean verified;

    public UsbController(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("usb", Context.MODE_PRIVATE);
    }

    // UI reads must never wait for the monitor held by a slow su operation.
    // SharedPreferences is thread-safe; every caller receives its own JSON copy.
    public JSONObject session() throws IOException {
        String value = preferences.getString("session", "");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(value);
        } catch (JSONException exception) {
            throw new IOException("USB 恢复记录损坏。请重启手机恢复系统 USB 后再清理应用数据。", exception);
        }
    }

    public boolean verified() {
        return verified;
    }

    public boolean isInUse(String path) throws IOException {
        JSONObject state = session();
        return state != null && path.equals(state.optString("image"));
    }

    public synchronized Map<String, String> inspect() throws IOException {
        verified = false;
        Map<String, String> info = execute("probe", Collections.emptyMap());
        JSONObject state = session();
        if (state != null && !state.optString("boot").equals(info.get("boot"))) {
            clearSession();
            state = null;
        }
        verified = state != null && (state.optString("backend").equals("configfs")
                ? state.optString("udc").equals(info.get("owned_udc"))
                        && !info.getOrDefault("owned_file", "").isEmpty()
                : "1".equals(info.get("enabled"))
                        && "mass_storage".equals(info.get("functions"))
                        && !info.getOrDefault("legacy_file", "").isEmpty());
        if (state != null) {
            String prefix = state.optString("backend").equals("configfs") ? "owned_" : "legacy_";
            try {
                state.put("actual_ro", info.getOrDefault(prefix + "ro", ""));
                state.put("actual_cdrom", info.getOrDefault(prefix + "cdrom", ""));
                state.put("actual_mode", actualMode(state.optString("actual_ro"), state.optString("actual_cdrom")));
                saveSession(state);
            } catch (JSONException exception) {
                throw new IOException(exception);
            }
        }
        preferences.edit().putString("diagnostics", diagnosticText(info)).apply();
        return info;
    }

    public synchronized long fileSize(String path) throws IOException {
        validateImagePath(path);
        Map<String, String> response = execute("stat", Collections.singletonMap("IMAGE", path));
        try {
            long size = Long.parseLong(response.getOrDefault("bytes", "0"));
            if (size <= 0) {
                throw new IOException("镜像不能为空。");
            }
            return size;
        } catch (NumberFormatException exception) {
            throw new IOException("无法读取镜像大小。", exception);
        }
    }

    public synchronized void mount(ImageEntry entry, Mode mode, String preferredBackend)
            throws IOException {
        Map<String, String> info = inspect();
        if (session() != null) {
            throw new IOException("请先弹出当前镜像或恢复 USB，再挂载其他镜像。");
        }
        if (info.containsKey("owned_udc")) {
            throw new IOException("发现没有恢复记录的挂载。请重启手机恢复 USB 后重试。");
        }
        validateImagePath(entry.path);
        boolean configfs = !preferredBackend.equals("legacy") && info.containsKey("base");
        if (preferredBackend.equals("configfs") && !configfs) {
            throw new IOException("此内核未提供 ConfigFS USB 接口。");
        }
        if (!configfs && !info.containsKey("lun")) {
            throw new IOException("未找到可用的 USB Mass Storage 接口。Root 本身无法补充缺失的内核驱动。");
        }
        if (!configfs && !info.getOrDefault("legacy_file", "").isEmpty()) {
            throw new IOException("传统 USB LUN 已被其他镜像占用，请先在原应用弹出。");
        }
        if (Integer.parseInt(info.getOrDefault("active_count", "0")) > 1) {
            throw new IOException("检测到多个 USB 控制器正在使用，需要专门适配。");
        }
        String udc = info.getOrDefault("udc", info.getOrDefault("available_udc", ""));
        if (configfs && udc.isEmpty()) {
            throw new IOException("未找到 USB Device Controller；请确认手机支持 USB 外设模式。");
        }
        try {
            JSONObject state = new JSONObject();
            state.put("backend", configfs ? "configfs" : "legacy");
            state.put("base", info.getOrDefault("base", ""));
            state.put("original", info.getOrDefault("original", ""));
            state.put("udc", udc);
            state.put("legacy", info.getOrDefault("legacy", ""));
            state.put("lun", info.getOrDefault("lun", ""));
            state.put("functions", info.getOrDefault("functions", ""));
            state.put("enabled", info.getOrDefault("enabled", "1"));
            state.put("old_ro", info.getOrDefault("old_ro", "0"));
            state.put("old_cdrom", info.getOrDefault("old_cdrom", "0"));
            state.put("image", entry.path);
            state.put("title", entry.title);
            state.put("mode", mode.label);
            state.put("requested_mode", mode.name());
            state.put("boot", info.getOrDefault("boot", ""));
            saveSession(state);
            Map<String, String> args = arguments(state);
            args.put("READONLY", mode.readonly);
            args.put("CDROM", mode.cdrom);
            verified = false;
            Map<String, String> result = execute(configfs ? "mount_configfs" : "mount_legacy", args);
            state.put("actual_ro", result.getOrDefault("actual_ro", ""));
            state.put("actual_cdrom", result.getOrDefault("actual_cdrom", ""));
            state.put("actual_mode", actualMode(state.optString("actual_ro"), state.optString("actual_cdrom")));
            saveSession(state);
            verified = true;
        } catch (JSONException exception) {
            throw new IOException(exception);
        }
    }

    public synchronized void eject() throws IOException {
        eject(null);
    }

    public synchronized void eject(String expectedImage) throws IOException {
        JSONObject state = session();
        if (state == null) {
            return;
        }
        if (expectedImage != null && !expectedImage.equals(state.optString("image"))) {
            throw new IOException("当前挂载的镜像已经改变，请重新选择，未卸载其他镜像。");
        }
        verified = false;
        Map<String, String> info = execute("probe", Collections.emptyMap());
        if (!state.optString("boot").equals(info.get("boot"))) {
            clearSession();
            return;
        }
        execute("unmount_" + state.optString("backend"), arguments(state));
        clearSession();
    }

    static String actualMode(String readonly, String cdrom) {
        if ("1".equals(cdrom) && "1".equals(readonly)) {
            return "只读 CD-ROM";
        }
        if (!"0".equals(cdrom)) {
            return "状态未知";
        }
        if ("0".equals(readonly)) {
            return "可写 USB（内核已确认）";
        }
        return "1".equals(readonly) ? "只读 USB" : "状态未知";
    }

    public String lastDiagnostics() {
        return preferences.getString("diagnostics", "尚未检测 USB。点击检测后会请求 Root 权限。");
    }

    public synchronized void diagnoseWrite() throws IOException {
        inspect();
        JSONObject state = session();
        RootShell.Result result = runCommand("diagnose_write", state == null ? Collections.emptyMap() : arguments(state));
        preferences.edit().putString("diagnostics", "DriveDroid Reborn · USB 写入诊断\n"
                + "请在电脑出现 CRC / I/O 错误后采集。此操作只读取状态和日志，不更改 USB。\n\n"
                + result.output()).apply();
    }

    public static void validateImagePath(String path) throws IOException {
        if (path == null || !path.startsWith("/") || !ImageFiles.isImage(path)) {
            throw new IOException("请输入 ISO / IMG 文件的绝对路径。");
        }
        try {
            RootShell.quote(path);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private Map<String, String> arguments(JSONObject state) {
        Map<String, String> args = new LinkedHashMap<>();
        for (String key : new String[]{"base", "original", "udc", "legacy", "lun", "functions", "enabled", "image", "old_ro", "old_cdrom"}) {
            args.put(key.toUpperCase(java.util.Locale.ROOT), state.optString(key));
        }
        return args;
    }

    private void saveSession(JSONObject state) throws IOException {
        if (!preferences.edit().putString("session", state.toString()).commit()) {
            throw new IOException("无法保存 USB 恢复记录，切换已取消。");
        }
    }

    private void clearSession() throws IOException {
        verified = false;
        if (!preferences.edit().remove("session").commit()) {
            throw new IOException("USB 已恢复，但无法更新本地记录。");
        }
    }

    private RootShell.Result runCommand(String action, Map<String, String> arguments)
            throws IOException {
        StringBuilder script = new StringBuilder("ACTION=").append(RootShell.quote(action)).append('\n');
        for (var entry : arguments.entrySet()) {
            script.append(entry.getKey()).append('=').append(RootShell.quote(entry.getValue())).append('\n');
        }
        try (var input = context.getAssets().open("usb.sh")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            script.append(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
        RootShell.Result result;
        try {
            result = root.run(script.toString());
            result.requireSuccess();
        } catch (IOException exception) {
            preferences.edit().putString("diagnostics", "最近的 USB 操作未完成：" + action
                    + "\n\n" + exception.getMessage() + "\n\n已有恢复记录会保留。").apply();
            throw exception;
        }
        return result;
    }

    private Map<String, String> execute(String action, Map<String, String> arguments)
            throws IOException {
        RootShell.Result result = runCommand(action, arguments);
        Map<String, String> response = new LinkedHashMap<>();
        for (String line : result.output().split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                response.put(line.substring(0, separator), line.substring(separator + 1).trim());
            }
        }
        String expected = action.startsWith("mount_") ? "mounted" :
                action.startsWith("unmount_") ? "unmounted" : action.equals("probe") ? "root" : "bytes";
        if (!response.containsKey(expected)) {
            throw new IOException("Root 操作没有返回完成标记，USB 恢复记录已保留。\n" + result.output());
        }
        return response;
    }

    private String diagnosticText(Map<String, String> info) {
        StringBuilder result = new StringBuilder("DriveDroid Reborn · USB 检测\n\n");
        info.forEach((key, value) -> result.append(key).append(": ").append(value).append('\n'));
        result.append("\n检测不等于挂载成功。请在电脑端验证 USB 枚举及引导。\n");
        return result.toString();
    }
}
