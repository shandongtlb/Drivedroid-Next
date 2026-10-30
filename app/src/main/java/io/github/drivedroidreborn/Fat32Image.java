package io.github.drivedroidreborn;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Creates an MBR-partitioned FAT32 disk without native mkfs dependencies. */
public final class Fat32Image {
    public static final long MIB = 1024L * 1024;
    private static final int PARTITION_START = 2048;
    private static final int RESERVED = 32;

    private Fat32Image() {
    }

    public static void create(File file, long bytes, boolean formatted) throws IOException {
        create(file, bytes, formatted, false, completed -> { });
    }

    public interface Progress {
        void update(long completed) throws IOException;
    }

    public static void create(File file, long bytes, boolean formatted,
                              boolean fullyAllocate, Progress progress) throws IOException {
        if (bytes < 64 * MIB || bytes > 128 * 1024 * MIB || bytes % 512 != 0) {
            throw new IOException("镜像大小必须在 64 MiB 到 128 GiB 之间。");
        }
        try (RandomAccessFile disk = new RandomAccessFile(file, "rw")) {
            if (disk.length() != 0) {
                throw new IOException("拒绝覆盖已有文件。");
            }
            disk.setLength(bytes);
            if (fullyAllocate) {
                byte[] zeros = new byte[256 * 1024];
                long position = 0;
                while (position < bytes) {
                    progress.update(position);
                    int count = (int) Math.min(zeros.length, bytes - position);
                    disk.write(zeros, 0, count);
                    position += count;
                }
            }
            if (formatted) {
                format(disk, bytes);
            }
            disk.getFD().sync();
            progress.update(bytes);
        }
    }

    private static void format(RandomAccessFile disk, long bytes) throws IOException {
        long partitionSectors = bytes / 512 - PARTITION_START;
        int sectorsPerCluster = bytes <= 512 * MIB ? 1 : bytes <= 32 * 1024 * MIB ? 8 : 64;
        long fatSectors = 1;
        while (true) {
            long clusters = (partitionSectors - RESERVED - 2 * fatSectors) / sectorsPerCluster;
            long required = ((clusters + 2) * 4 + 511) / 512;
            if (required <= fatSectors) {
                break;
            }
            fatSectors = required;
        }
        long clusters = (partitionSectors - RESERVED - 2 * fatSectors) / sectorsPerCluster;
        if (clusters < 65525 || clusters >= 0x0ffffff5L) {
            throw new IOException("此容量不适合 FAT32。");
        }

        ByteBuffer mbr = sector();
        mbr.position(446);
        mbr.put((byte) 0).put(new byte[]{(byte) 0xfe, (byte) 0xff, (byte) 0xff});
        mbr.put((byte) 0x0c).put(new byte[]{(byte) 0xfe, (byte) 0xff, (byte) 0xff});
        mbr.putInt(PARTITION_START).putInt((int) partitionSectors);
        signature(mbr);
        write(disk, 0, mbr);

        ByteBuffer boot = sector();
        boot.put(new byte[]{(byte) 0xeb, 0x58, (byte) 0x90});
        boot.put("MSWIN4.1".getBytes(StandardCharsets.US_ASCII));
        boot.putShort((short) 512).put((byte) sectorsPerCluster);
        boot.putShort((short) RESERVED).put((byte) 2);
        boot.putShort((short) 0).putShort((short) 0).put((byte) 0xf8);
        boot.putShort((short) 0).putShort((short) 63).putShort((short) 255);
        boot.putInt(PARTITION_START).putInt((int) partitionSectors);
        boot.putInt((int) fatSectors).putShort((short) 0).putShort((short) 0);
        boot.putInt(2).putShort((short) 1).putShort((short) 6);
        boot.position(64);
        boot.put((byte) 0x80).put((byte) 0).put((byte) 0x29);
        boot.putInt((int) System.currentTimeMillis());
        boot.put("DRIVEDROID ".getBytes(StandardCharsets.US_ASCII));
        boot.put("FAT32   ".getBytes(StandardCharsets.US_ASCII));
        signature(boot);
        write(disk, PARTITION_START, boot);
        write(disk, PARTITION_START + 6, boot);

        ByteBuffer info = sector();
        info.putInt(0, 0x41615252);
        info.putInt(484, 0x61417272);
        info.putInt(488, (int) clusters - 1);
        info.putInt(492, 3);
        info.putInt(508, 0xaa550000);
        write(disk, PARTITION_START + 1, info);
        write(disk, PARTITION_START + 7, info);

        ByteBuffer fat = sector();
        fat.putInt(0x0ffffff8).putInt(0xffffffff).putInt(0x0fffffff);
        write(disk, PARTITION_START + RESERVED, fat);
        write(disk, PARTITION_START + RESERVED + fatSectors, fat);

        ByteBuffer root = sector();
        root.put("DRIVEDROID ".getBytes(StandardCharsets.US_ASCII)).put((byte) 0x08);
        write(disk, PARTITION_START + RESERVED + 2 * fatSectors, root);
    }

    private static ByteBuffer sector() {
        return ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void signature(ByteBuffer buffer) {
        buffer.putShort(510, (short) 0xaa55);
    }

    private static void write(RandomAccessFile disk, long sector, ByteBuffer bytes)
            throws IOException {
        disk.seek(sector * 512);
        disk.write(bytes.array());
    }
}
