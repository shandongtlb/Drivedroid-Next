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
}
