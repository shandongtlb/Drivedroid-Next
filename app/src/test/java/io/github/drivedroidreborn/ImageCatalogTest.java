package io.github.drivedroidreborn;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class ImageCatalogTest {
    @Test
    public void readsOriginalRepositoryFormatAndResolvesRelativeLinks() throws Exception {
        String json = """
                [
                    {
                        "id": "example",
                        "name": "Example Linux",
                        "releases": [
                            {
                                "version": "1.0",
                                "arch": "amd64",
                                "url": "images/live.iso",
                                "size": 4194304
                            }
                        ]
                    }
                ]
                """;
        var catalog = ImageCatalog.parse(json, "https://example.org/repository/main.json");
        assertEquals(1, catalog.size());
        var release = catalog.get(0).releases().get(0);
        assertEquals("https://example.org/repository/images/live.iso", release.url());
        assertEquals("Example Linux 1.0 amd64", release.name());
        assertEquals("live.iso", release.filename());
        assertEquals(4194304, release.bytes());
    }

    @Test
    public void excludesInsecureLinksArchivesAndExecutablePayloads() throws Exception {
        String json = """
                [
                    {
                        "name": "Example",
                        "releases": [
                            {"url": "http://example.org/old.iso"},
                            {"url": "https://example.org/image.img.xz"},
                            {"url": "file:///tmp/disk.img"},
                            {"url": "https://example.org/image.iso.exe"},
                            {"url": "https://example.org/good.img"}
                        ]
                    }
                ]
                """;
        var catalog = ImageCatalog.parse(json, "https://example.org/repo.json");
        assertEquals(1, catalog.size());
        assertEquals(1, catalog.get(0).releases().size());
        assertEquals("good.img", catalog.get(0).releases().get(0).filename());
    }

    @Test
    public void rejectsMalformedOrUnexpectedRepositoryData() {
        for (String value : new String[]{"not-json", "{}", "[{\"name\":\"bad\"}]"}) {
            assertThrows(IOException.class, () -> ImageCatalog.parse(value, "https://example.org/repo.json"));
        }
    }
}
