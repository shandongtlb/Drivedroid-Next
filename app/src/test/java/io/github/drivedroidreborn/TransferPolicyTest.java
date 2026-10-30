package io.github.drivedroidreborn;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class TransferPolicyTest {
    @Test
    public void rejectsMissingAndMalformedDownloadChecksums() throws Exception {
        for (String checksum : new String[]{null, "", "  ", "abcd", "g".repeat(64)}) {
            assertThrows(IOException.class, () -> TransferPolicy.requireChecksum(checksum));
        }
        assertEquals("A".repeat(64), TransferPolicy.requireChecksum(" " + "A".repeat(64) + " "));
    }

    @Test
    public void boundsUnknownLengthStreamsBeforeExceedingMaximum() {
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> ImageFiles.copy(
                new ByteArrayInputStream(new byte[1025]), destination, bytes -> { }, 1024));
        assertTrue(destination.size() <= 1024);
    }

    @Test
    public void stopsWhenFreeSpaceFallsDuringCopy() {
        AtomicLong available = new AtomicLong(TransferPolicy.RESERVED_BYTES + 1024 * 1024);
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> ImageFiles.copy(
                new ByteArrayInputStream(new byte[1024 * 1024]), destination, bytes -> {
                    if (bytes > 0) {
                        available.set(TransferPolicy.RESERVED_BYTES - 1);
                    }
                    TransferPolicy.requireSpace(available.get(), 256 * 1024);
                }));
        assertTrue(destination.size() < 1024 * 1024);
    }

    @Test
    public void checksSpaceWithoutIntegerOverflow() throws Exception {
        TransferPolicy.requireSpace(TransferPolicy.RESERVED_BYTES + 10, 10);
        assertThrows(IOException.class,
                () -> TransferPolicy.requireSpace(TransferPolicy.RESERVED_BYTES + 9, 10));
        assertThrows(IOException.class, () -> TransferPolicy.requireSpace(Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IOException.class, () -> TransferPolicy.requireSpace(-1, 0));
    }
}
