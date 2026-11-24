package io.github.katehok.shortener.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileNoticeStoreTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path dir;

    @Test
    void startsEmptyWithoutFile() throws IOException {
        Path path = dir.resolve("notices.tsv");
        FileNoticeStore store = new FileNoticeStore(path);
        assertTrue(store.take(ALICE).isEmpty());
        assertFalse(Files.exists(path));
    }

    @Test
    void noticesSurviveReload() throws IOException {
        Path path = dir.resolve("data/notices.tsv");
        FileNoticeStore store = new FileNoticeStore(path);
        store.add(ALICE, "first");
        store.add(BOB, "for bob");
        store.add(ALICE, "second");

        FileNoticeStore reloaded = new FileNoticeStore(path);
        assertEquals(List.of("first", "second"), reloaded.take(ALICE));
        assertEquals(List.of("for bob"), reloaded.take(BOB));
        assertFalse(Files.exists(path.resolveSibling("notices.tsv.tmp")));
    }

    @Test
    void takeClearsOnlyOwnerNotices() throws IOException {
        Path path = dir.resolve("notices.tsv");
        FileNoticeStore store = new FileNoticeStore(path);
        store.add(ALICE, "first");
        store.add(BOB, "for bob");
        assertEquals(List.of("first"), store.take(ALICE));
        assertTrue(store.take(ALICE).isEmpty());

        FileNoticeStore reloaded = new FileNoticeStore(path);
        assertTrue(reloaded.take(ALICE).isEmpty());
        assertEquals(List.of("for bob"), reloaded.take(BOB));
    }

    @Test
    void tabsAndNewlinesBecomeSpaces() throws IOException {
        Path path = dir.resolve("notices.tsv");
        new FileNoticeStore(path).add(ALICE, "a\tb\nc\r\nd");
        assertEquals(List.of("a b c  d"), new FileNoticeStore(path).take(ALICE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "not-a-uuid\ttext", "11111111-1111-1111-1111-111111111111\t", "a\tb\tc"})
    void failsOnBrokenFile(String line) throws IOException {
        Path path = dir.resolve("notices.tsv");
        Files.writeString(path, ALICE + "\tok\n\n" + line + "\n");
        IOException e = assertThrows(IOException.class, () -> new FileNoticeStore(path));
        assertEquals("Broken line 3 in " + path, e.getMessage());
    }

    @Test
    void failsOnNonUtf8File() throws IOException {
        Path path = dir.resolve("notices.tsv");
        Files.write(path, new byte[] {(byte) 0xff, (byte) 0xfe, 'a', '\n'});
        IOException e = assertThrows(IOException.class, () -> new FileNoticeStore(path));
        assertEquals(path + " is not a valid UTF-8 file", e.getMessage());
    }

    @Test
    void failedWriteLeavesMemoryAsOnDisk() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path data = dir.resolve("data");
        Path path = data.resolve("notices.tsv");
        FileNoticeStore store = new FileNoticeStore(path);
        store.add(ALICE, "first");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(data);
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(data));
            assertThrows(UncheckedIOException.class, () -> store.add(BOB, "lost"));
            assertThrows(UncheckedIOException.class, () -> store.take(ALICE));
            assertTrue(store.take(BOB).isEmpty());
        } finally {
            Files.setPosixFilePermissions(data, perms);
        }
        assertEquals(List.of("first"), store.take(ALICE));
    }
}
