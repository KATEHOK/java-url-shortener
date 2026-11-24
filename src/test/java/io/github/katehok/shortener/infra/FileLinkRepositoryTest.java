package io.github.katehok.shortener.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.katehok.shortener.MutableClock;
import io.github.katehok.shortener.RecordingNotifier;
import io.github.katehok.shortener.core.CodeGenerator;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.Settings;
import io.github.katehok.shortener.core.ShortLink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLinkRepositoryTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    @TempDir
    Path dir;

    private ShortLink link(String code, UUID ownerId) {
        String url = "https://example.com/" + code + "?q=1&x=y";
        return new ShortLink(code, url, ownerId, NOW, NOW.plus(Duration.ofDays(1)), 5, 2);
    }

    private void assertSameLink(ShortLink expected, ShortLink actual) {
        assertEquals(expected.getCode(), actual.getCode());
        assertEquals(expected.getUrl(), actual.getUrl());
        assertEquals(expected.getOwnerId(), actual.getOwnerId());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getExpiresAt(), actual.getExpiresAt());
        assertEquals(expected.getLimit(), actual.getLimit());
        assertEquals(expected.getClicks(), actual.getClicks());
    }

    private LinkService service(FileLinkRepository repository) {
        Settings settings = new Settings(Duration.ofHours(1), 10, 1000, 2048, 10);
        RecordingNotifier notifier = new RecordingNotifier();
        return new LinkService(repository, CodeGenerator.random(7), notifier, new MutableClock(), settings);
    }

    @Test
    void startsEmptyWithoutFile() throws IOException {
        FileLinkRepository repository = new FileLinkRepository(dir.resolve("links.tsv"));
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void savedLinkSurvivesReload() throws IOException {
        Path path = dir.resolve("nested/data/links.tsv");
        ShortLink link = link("abc1234", ALICE);
        new FileLinkRepository(path).save(link);

        FileLinkRepository reloaded = new FileLinkRepository(path);
        assertTrue(reloaded.exists("abc1234"));
        assertSameLink(link, reloaded.find("abc1234").orElseThrow());
        assertFalse(Files.exists(path.resolveSibling("links.tsv.tmp")));
    }

    @Test
    void deleteIsPersisted() throws IOException {
        Path path = dir.resolve("links.tsv");
        FileLinkRepository repository = new FileLinkRepository(path);
        repository.save(link("aaaaaaa", ALICE));
        repository.save(link("bbbbbbb", ALICE));
        repository.delete("aaaaaaa");

        FileLinkRepository reloaded = new FileLinkRepository(path);
        assertFalse(reloaded.exists("aaaaaaa"));
        assertTrue(reloaded.exists("bbbbbbb"));
    }

    @Test
    void clicksArePersisted() throws IOException {
        Path path = dir.resolve("links.tsv");
        LinkService service = service(new FileLinkRepository(path));
        String code = service.shorten(ALICE, "https://example.com").getCode();
        service.open(code);
        service.open(code);

        assertEquals(2, new FileLinkRepository(path).find(code).orElseThrow().getClicks());
    }

    @Test
    void findByOwnerWorksAfterReload() throws IOException {
        Path path = dir.resolve("links.tsv");
        FileLinkRepository repository = new FileLinkRepository(path);
        repository.save(link("aaaaaaa", ALICE));
        repository.save(link("bbbbbbb", BOB));
        repository.save(link("ccccccc", ALICE));

        List<ShortLink> links = new FileLinkRepository(path).findByOwner(ALICE);
        assertEquals(List.of("aaaaaaa", "ccccccc"), links.stream().map(ShortLink::getCode).toList());
    }

    @Test
    void failsOnBrokenFile() throws IOException {
        Path path = dir.resolve("links.tsv");
        Files.writeString(path, "abc\tdef\n");
        IOException e = assertThrows(IOException.class, () -> new FileLinkRepository(path));
        assertEquals("Broken line 1 in " + path, e.getMessage());
    }

    @Test
    void failsOnBadFieldValue() throws IOException {
        Path path = dir.resolve("links.tsv");
        FileLinkRepository repository = new FileLinkRepository(path);
        repository.save(link("aaaaaaa", ALICE));
        String line = "bbbbbbb\thttps://example.com\t" + BOB + "\tyesterday\t" + NOW + "\t5\t0\n";
        Files.writeString(path, Files.readString(path) + line);
        IOException e = assertThrows(IOException.class, () -> new FileLinkRepository(path));
        assertEquals("Broken line 2 in " + path, e.getMessage());
    }

    @Test
    void failsOnNonUtf8File() throws IOException {
        Path path = dir.resolve("links.tsv");
        Files.write(path, new byte[] {(byte) 0xff, (byte) 0xfe, 'a', '\n'});
        IOException e = assertThrows(IOException.class, () -> new FileLinkRepository(path));
        assertEquals(path + " is not a valid UTF-8 file", e.getMessage());
    }

    @Test
    void failedWriteLeavesMemoryAsOnDisk() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path data = dir.resolve("data");
        Path path = data.resolve("links.tsv");
        FileLinkRepository repository = new FileLinkRepository(path);
        LinkService service = service(repository);
        String code = service.shorten(ALICE, "https://example.com").getCode();
        service.open(code);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(data);
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(data));
            assertThrows(UncheckedIOException.class, () -> service.shorten(ALICE, "https://example.org"));
            assertThrows(UncheckedIOException.class, () -> service.open(code));
            assertThrows(UncheckedIOException.class, () -> service.delete(ALICE, code));
            assertEquals(List.of(code), repository.findAll().stream().map(ShortLink::getCode).toList());
            assertEquals(1, repository.find(code).orElseThrow().getClicks());
        } finally {
            Files.setPosixFilePermissions(data, perms);
        }
        assertEquals(1, new FileLinkRepository(path).find(code).orElseThrow().getClicks());
    }
}
