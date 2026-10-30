package io.github.drivedroidreborn;

import org.junit.Test;

import static org.junit.Assert.*;

public class RootShellTest {
    @Test
    public void treatsShellMetacharactersAsLiteralPathData() {
        assertEquals("'/storage/My disk;$(id).iso'", RootShell.quote("/storage/My disk;$(id).iso"));
        assertEquals("'/storage/it'\"'\"'s.iso'", RootShell.quote("/storage/it's.iso"));
        assertEquals("''", RootShell.quote(""));
    }

    @Test
    public void rejectsCharactersThatBreakCommandOrStatusFraming() {
        for (String value : new String[]{"a\nb", "a\rb", "a\0b"}) {
            assertThrows(IllegalArgumentException.class, () -> RootShell.quote(value));
        }
        assertThrows(IllegalArgumentException.class, () -> RootShell.quote(null));
    }

    @Test(timeout = 5000)
    public void timeoutAlsoCoversBlockedStdinWhileWaitingForRootAuthorization() {
        BlockingProcess process = new BlockingProcess();
        var exception = assertThrows(java.io.IOException.class,
                () -> RootShell.runProcess(process, "x".repeat(100000), 30));
        assertTrue(exception.getMessage().contains("超时"));
        assertFalse(process.isAlive());
    }

    private static final class BlockingProcess extends Process {
        private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);

        @Override
        public java.io.OutputStream getOutputStream() {
            return new java.io.OutputStream() {
                @Override
                public void write(int value) throws java.io.IOException {
                    try {
                        stopped.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    throw new java.io.IOException("closed");
                }
            };
        }

        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return getInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            stopped.await();
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return stopped.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException();
            }
            return 1;
        }

        @Override
        public void destroy() {
            stopped.countDown();
        }

        @Override
        public boolean isAlive() {
            return stopped.getCount() != 0;
        }
    }
}
