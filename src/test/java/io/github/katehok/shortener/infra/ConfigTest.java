package io.github.katehok.shortener.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.core.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    @TempDir
    Path dir;

    private Path write(String txt) throws IOException {
        Path path = dir.resolve("custom.properties");
        Files.writeString(path, txt);
        return path;
    }

    @Test
    void loadsDefaults() throws IOException {
        Config config = Config.load();
        assertEquals(new Settings(Duration.ofDays(1), 10, 1000, 2048, 10), config.getSettings());
        assertEquals("clck.ru/", config.getLinkPrefix());
        assertEquals(7, config.getCodeLength());
        assertEquals(Duration.ofMinutes(1), config.getCleanupPeriod());
        assertEquals(Path.of("data/links.tsv"), config.getLinksFile());
        assertEquals(Path.of("data/user.id"), config.getUserFile());
        assertEquals(Path.of("data/notices.tsv"), config.getNoticesFile());
    }

    @Test
    void externalFileOverridesSomeKeys() throws IOException {
        Config config = Config.load(write("link.ttl=PT2H\nlink.limit.max=50\nstorage.links=other/links.tsv\n"));
        assertEquals(new Settings(Duration.ofHours(2), 10, 50, 2048, 10), config.getSettings());
        assertEquals(Path.of("other/links.tsv"), config.getLinksFile());
        assertEquals("clck.ru/", config.getLinkPrefix());
        assertEquals(7, config.getCodeLength());
        assertEquals(Path.of("data/user.id"), config.getUserFile());
    }

    @Test
    void rejectsBadDuration() throws IOException {
        Path path = write("link.ttl=abc\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertEquals("Bad value for link.ttl: abc", e.getMessage());
    }

    @Test
    void rejectsNonPositiveDuration() throws IOException {
        Path path = write("cleanup.period=PT0S\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(path));
    }

    @Test
    void rejectsDefaultLimitAboveMax() throws IOException {
        Path path = write("link.limit.default=20\nlink.limit.max=5\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(path));
    }

    @Test
    void rejectsBadInt() throws IOException {
        Path path = write("code.attempts=0\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertEquals("Bad value for code.attempts: 0", e.getMessage());
    }

    @Test
    void rejectsCodeLengthOutOfRange() throws IOException {
        Path shortPath = write("code.length=3\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(shortPath));
        Path longPath = write("code.length=33\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(longPath));
    }

    @Test
    void rejectsEmptyValue() throws IOException {
        Path path = write("link.prefix=\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertTrue(e.getMessage().contains("link.prefix"));
    }

    @Test
    void failsOnMissingExternalFile() {
        assertThrows(NoSuchFileException.class, () -> Config.load(dir.resolve("missing.properties")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"link.ttl=PT59S", "link.ttl=P3651D", "cleanup.period=PT0.5S", "cleanup.period=P2D"})
    void rejectsDurationOutOfRange(String line) throws IOException {
        Path path = write(line + "\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertEquals("Bad value for " + line.replace("=", ": "), e.getMessage());
    }

    @Test
    void acceptsDurationBounds() throws IOException {
        Config config = Config.load(write("link.ttl=PT1M\ncleanup.period=PT1S\n"));
        assertEquals(Duration.ofMinutes(1), config.getSettings().ttl());
        assertEquals(Duration.ofSeconds(1), config.getCleanupPeriod());
        config = Config.load(write("link.ttl=P3650D\ncleanup.period=P1D\n"));
        assertEquals(Duration.ofDays(3650), config.getSettings().ttl());
        assertEquals(Duration.ofDays(1), config.getCleanupPeriod());
    }

    @Test
    void rejectsUnknownKey() throws IOException {
        Path path = write("link.ttl=PT2H\nlink.tll=PT1H\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertEquals("Unknown key in config: link.tll", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "storage.links=data/user.id|storage.links and storage.user",
        "storage.notices=./data/../data/links.tsv|storage.links and storage.notices",
        "storage.user=data/x\nstorage.notices=data/x|storage.user and storage.notices",
    })
    void rejectsEqualStoragePaths(String src) throws IOException {
        String[] parts = src.split("\\|");
        Path path = write(parts[0] + "\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertEquals(parts[1] + " must be different files", e.getMessage());
    }

    @Test
    void rejectsNonUtf8File() throws IOException {
        Path path = dir.resolve("custom.properties");
        Files.write(path, new byte[] {'l', 'i', 'n', 'k', '.', 't', 't', 'l', '=', (byte) 0xff, '\n'});
        IOException e = assertThrows(IOException.class, () -> Config.load(path));
        assertEquals(path + " is not a valid UTF-8 file", e.getMessage());
    }

    @Test
    void rejectsDirectoryAndEmptyPath() {
        IOException e = assertThrows(IOException.class, () -> Config.load(dir));
        assertEquals(dir + " is a directory", e.getMessage());
        e = assertThrows(IOException.class, () -> Config.load(Path.of("")));
        assertEquals("Config path is empty", e.getMessage());
    }

    @Test
    void rejectsPrefixWithSpaces() throws IOException {
        Path path = write("link.prefix=clck ru/\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(path));
        assertEquals("Bad value for link.prefix: clck ru/", e.getMessage());
    }
}
