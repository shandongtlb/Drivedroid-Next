package io.github.drivedroidreborn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Implements the public DriveDroid repository JSON format. */
public final class ImageCatalog {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    public record Release(String name, String url, String filename, long bytes, String sha256) {
    }

    public record Distribution(String name, List<Release> releases) {
    }

    public static List<Distribution> fetch(String address) throws IOException {
        URL url;
        try {
            url = new URI(address).toURL();
        } catch (Exception exception) {
            throw new IOException("仓库地址无效。", exception);
        }
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!url.getProtocol().equalsIgnoreCase("https")) {
                throw new IOException("仓库及其跳转地址必须使用 HTTPS。");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("仓库跳转缺少地址。");
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("仓库返回 HTTP " + status);
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                long deadline = System.nanoTime() + 30_000_000_000L;
                try (InputStream input = connection.getInputStream()) {
                    byte[] block = new byte[8192];
                    int count;
                    while ((count = input.read(block)) != -1) {
                        if (System.nanoTime() > deadline) {
                            throw new IOException("仓库读取超时，请稍后重试。");
                        }
                        if (bytes.size() + count > MAX_BYTES) {
                            throw new IOException("仓库文件超过 2 MiB 限制。");
                        }
                        bytes.write(block, 0, count);
                    }
                }
                return parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8), url.toString());
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("仓库跳转过多。");
    }

    public static List<Distribution> parse(String json, String baseAddress) throws IOException {
        try {
            URL base = new URL(baseAddress);
            JSONArray distributions = new JSONArray(json);
            List<Distribution> result = new ArrayList<>();
            int releaseCount = 0;
            for (int index = 0; index < distributions.length(); index++) {
                if (index >= 1000) {
                    throw new IOException("仓库发行版数量超过限制。");
                }
                JSONObject distro = distributions.getJSONObject(index);
                String name = distro.getString("name");
                JSONArray images = distro.getJSONArray("releases");
                List<Release> releases = new ArrayList<>();
                for (int releaseIndex = 0; releaseIndex < images.length(); releaseIndex++) {
                    if (++releaseCount > 20000) {
                        throw new IOException("仓库镜像数量超过限制。");
                    }
                    JSONObject item = images.getJSONObject(releaseIndex);
                    URL download = new URL(base, item.getString("url"));
                    String path = download.getPath();
                    String filename = path.substring(path.lastIndexOf('/') + 1);
                    if (!download.getProtocol().equalsIgnoreCase("https") || !ImageFiles.isImage(filename)) {
                        continue;
                    }
                    String label = (name + " " + item.optString("version") + " " + item.optString("arch")).trim();
                    releases.add(new Release(label, download.toString(), ImageFiles.safeName(filename),
                            item.optLong("size", -1), item.optString("sha256", "")));
                }
                if (!releases.isEmpty()) {
                    result.add(new Distribution(name, releases));
                }
            }
            result.sort(java.util.Comparator.comparing(Distribution::name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IOException("仓库格式不正确，需要 DriveDroid JSON 镜像仓库。", exception);
        }
    }
}
