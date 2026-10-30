package io.github.drivedroidreborn;

import java.io.IOException;

public final class TransferPolicy {
    public static final long MAX_IMAGE_BYTES = 128L * 1024 * 1024 * 1024;
    public static final long RESERVED_BYTES = 256L * 1024 * 1024;

    private TransferPolicy() {
    }

    public static void requireSpace(long available, long incoming) throws IOException {
        if (incoming < 0 || incoming > MAX_IMAGE_BYTES) {
            throw new IOException("单个镜像最大允许 128 GiB。");
        }
        if (available < RESERVED_BYTES || incoming > available - RESERVED_BYTES) {
            throw new IOException("剩余空间不足，已停止写入以保留至少 256 MiB 空间。");
        }
    }

    public static String requireChecksum(String checksum) throws IOException {
        String value = checksum == null ? "" : checksum.trim();
        if (!value.matches("(?i)[a-f0-9]{64}")) {
            throw new IOException("下载前必须填写从发行方可信渠道取得的 64 位 SHA-256。");
        }
        return value;
    }
}
