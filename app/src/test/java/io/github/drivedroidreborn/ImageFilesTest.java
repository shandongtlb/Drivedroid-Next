package io.github.drivedroidreborn;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ImageFilesTest {
    @Test
    public void copyPreservesBinaryDataAcrossMultipleBuffers() throws Exception {
        byte[] input = new byte[1024 * 1024 + 37];
        new java.util.Random(123).nextBytes(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong progress = new AtomicLong();
        long copied = ImageFiles.copy(new ByteArrayInputStream(input), output, progress::set);
        assertEquals(input.length, copied);
        assertEquals(input.length, progress.get());
        assertArrayEquals(input, output.toByteArray());
    }

    @Test
    public void cancellationIsPropagatedInsteadOfReportingSuccess() {
        assertThrows(IOException.class, () -> ImageFiles.copy(
                new ByteArrayInputStream(new byte[1024]), new ByteArrayOutputStream(),
                bytes -> {
                    throw new IOException("cancelled");
                }));
    }

    @Test
    public void filenamesCannotEscapeDestinationDirectory() {
        for (String value : new String[]{"../../disk.img", "..\\..\\disk.img", "/tmp/image.iso", "x\ny.iso"}) {
            String name = ImageFiles.safeName(value);
            assertFalse(name.contains("/"));
            assertFalse(name.contains("\\"));
            assertFalse(name.contains("\n"));
            assertTrue(ImageFiles.isImage(name));
        }
        assertEquals("image.img", ImageFiles.safeName(".."));
        assertEquals("disk.ISO", ImageFiles.safeName("disk.ISO"));
        assertFalse(ImageFiles.isImage("file.iso.exe"));
    }
}
