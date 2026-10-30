package io.github.drivedroidreborn;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class Fat32ImageTest {
    private File image(String prefix) throws Exception {
        // Artifacts are retained for inspection; tests never delete existing files.
        File directory = new File("build/test-images");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return Files.createTempFile(directory.toPath(), prefix, ".img").toFile();
    }

    @Test
    public void fat32HasConsistentMbrBpbFsInfoAndMirroredFat() throws Exception {
        File image = image("fat32-");
        long capacity = 64 * Fat32Image.MIB;
        Fat32Image.create(image, capacity, true);
        try (RandomAccessFile disk = new RandomAccessFile(image, "r")) {
            assertEquals(capacity, disk.length());
            ByteBuffer mbr = read(disk, 0);
            assertEquals(0xaa55, Short.toUnsignedInt(mbr.getShort(510)));
            assertEquals(0x0c, mbr.get(450));
            int firstSector = mbr.getInt(454);
            int sectors = mbr.getInt(458);
            assertEquals(2048, firstSector);
            assertEquals(capacity / 512, firstSector + sectors);

            ByteBuffer boot = read(disk, firstSector);
            assertEquals(512, boot.getShort(11));
            int clusterSectors = boot.get(13) & 0xff;
            int reserved = boot.getShort(14);
            int fatCount = boot.get(16);
            int fatSize = boot.getInt(36);
            assertEquals(2, fatCount);
            assertEquals(sectors, boot.getInt(32));
            assertEquals(firstSector, boot.getInt(28));
            assertEquals(2, boot.getInt(44));
            assertArrayEquals(boot.array(), read(disk, firstSector + 6).array());

            long clusters = (sectors - reserved - fatCount * fatSize) / clusterSectors;
            assertTrue(clusters >= 65525);
            assertTrue((clusters + 2) * 4 <= fatSize * 512L);
            ByteBuffer fat = read(disk, firstSector + reserved);
            assertEquals(0x0ffffff8, fat.getInt(0));
            assertEquals(0x0fffffff, fat.getInt(8));
            assertArrayEquals(fat.array(), read(disk, firstSector + reserved + fatSize).array());

            ByteBuffer fsInfo = read(disk, firstSector + 1);
            assertEquals(0x41615252, fsInfo.getInt(0));
            assertEquals(0x61417272, fsInfo.getInt(484));
            assertEquals(clusters - 1, fsInfo.getInt(488));
            assertEquals(0xaa550000, fsInfo.getInt(508));
            ByteBuffer root = read(disk, firstSector + reserved + fatCount * fatSize);
            assertEquals(0x08, root.get(11));
        }
    }

    @Test
    public void unformattedImageHasRequestedSizeAndNoPartitionTable() throws Exception {
        File image = image("raw-");
        Fat32Image.create(image, 64 * Fat32Image.MIB, false);
        try (RandomAccessFile disk = new RandomAccessFile(image, "r")) {
            assertEquals(64 * Fat32Image.MIB, disk.length());
            assertEquals(0, read(disk, 0).getShort(510));
        }
    }

    @Test
    public void neverOverwritesAnExistingImage() throws Exception {
        File image = image("existing-");
        Files.write(image.toPath(), new byte[]{1, 2, 3, 4});
        assertThrows(java.io.IOException.class,
                () -> Fat32Image.create(image, 64 * Fat32Image.MIB, true));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(image.toPath()));
    }

    @Test
    public void rejectsInvalidAndOverflowingCapacityBeforeWriting() throws Exception {
        File image = image("invalid-");
        for (long size : new long[]{-1, 0, 63 * Fat32Image.MIB, Long.MAX_VALUE}) {
            assertThrows(java.io.IOException.class, () -> Fat32Image.create(image, size, true));
        }
        assertEquals(0, image.length());
    }

    private ByteBuffer read(RandomAccessFile disk, long sector) throws Exception {
        byte[] block = new byte[512];
        disk.seek(sector * 512);
        disk.readFully(block);
        return ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
    }
}
