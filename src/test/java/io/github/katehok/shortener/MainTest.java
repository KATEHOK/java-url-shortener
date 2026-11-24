package io.github.katehok.shortener;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private PrintStream oldErr;
    private PrintStream oldOut;
    private InputStream oldIn;

    @BeforeEach
    void setUp() {
        oldErr = System.err;
        oldOut = System.out;
        oldIn = System.in;
        System.setErr(new PrintStream(errors, true, UTF_8));
        System.setOut(new PrintStream(output, true, UTF_8));
        System.setIn(new ByteArrayInputStream(new byte[0]));
    }

    @AfterEach
    void tearDown() {
        System.setErr(oldErr);
        System.setOut(oldOut);
        System.setIn(oldIn);
    }

    private String toConfigPath(Path path) {
        return path.toString().replace("\\", "/");
    }

    private Path writeConfig(String txt) throws IOException {
        Path path = dir.resolve("my.properties");
        Files.writeString(path, txt);
        return path;
    }

    @Test
    void stopsOnUnknownConfigKey() throws IOException {
        Path config = writeConfig("link.color=red\n");
        assertFalse(Main.run(new String[] {config.toString()}));
        assertEquals("Can't load config: Unknown key in config: link.color\n", errors.toString(UTF_8));
    }

    @Test
    void stopsOnMissingConfig() {
        Path config = dir.resolve("missing.properties");
        assertFalse(Main.run(new String[] {config.toString()}));
        assertEquals("Can't load config: no such file " + config + "\n", errors.toString(UTF_8));
    }

    @Test
    void stopsOnBrokenLinksFile() throws IOException {
        Path links = dir.resolve("links.tsv");
        Files.writeString(links, "broken\n");
        Path config = writeConfig("storage.links=" + toConfigPath(links) + "\n");
        assertFalse(Main.run(new String[] {config.toString()}));
        assertEquals("Can't load links from " + links + ": Broken line 1 in " + links + "\n", errors.toString(UTF_8));
    }

    @Test
    void stopsOnBrokenNoticesFile() throws IOException {
        Path links = dir.resolve("links.tsv");
        Path notices = dir.resolve("notices.tsv");
        Files.writeString(notices, "broken\n");
        Path config = writeConfig("storage.links=" + toConfigPath(links)
                + "\nstorage.notices=" + toConfigPath(notices) + "\n");
        assertFalse(Main.run(new String[] {config.toString()}));
        String expected = "Can't load notices from " + notices + ": Broken line 1 in " + notices + "\n";
        assertEquals(expected, errors.toString(UTF_8));
    }

    @Test
    void stopsOnNonUtf8Config() throws IOException {
        Path config = dir.resolve("my.properties");
        Files.write(config, new byte[] {'a', '=', (byte) 0xff, '\n'});
        assertFalse(Main.run(new String[] {config.toString()}));
        assertEquals("Can't load config: " + config + " is not a valid UTF-8 file\n", errors.toString(UTF_8));
    }

    @Test
    void helpPrintsUsage() {
        String usage = "Usage: java -jar url-shortener.jar [config.properties]\n";
        assertTrue(Main.run(new String[] {"--help"}));
        assertTrue(Main.run(new String[] {"-h"}));
        assertEquals(usage + usage, output.toString(UTF_8));
        assertEquals("", errors.toString(UTF_8));
    }

    @Test
    void tooManyArgumentsPrintUsage() {
        assertFalse(Main.run(new String[] {"a.properties", "b.properties"}));
        assertEquals("Usage: java -jar url-shortener.jar [config.properties]\n", errors.toString(UTF_8));
        assertEquals("", output.toString(UTF_8));
    }

    @Test
    void linkExpiredWhileOffIsNoticedRightAfterGreeting() throws IOException {
        UUID owner = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Path links = dir.resolve("links.tsv");
        Path user = dir.resolve("user.id");
        Path notices = dir.resolve("notices.tsv");
        Files.writeString(links, "abc1234\thttps://example.com\t" + owner
                + "\t2020-01-01T00:00:00Z\t2020-01-02T00:00:00Z\t10\t0\n");
        Files.writeString(user, owner + "\n");
        Path config = writeConfig("storage.links=" + toConfigPath(links) + "\nstorage.user=" + toConfigPath(user)
                + "\nstorage.notices=" + toConfigPath(notices) + "\n");

        assertTrue(Main.run(new String[] {config.toString()}));
        String txt = output.toString(UTF_8);
        assertTrue(txt.startsWith("Hi, " + owner + "! Type help to see commands\n"
                + "Notice: Your link clck.ru/abc1234 expired and was deleted. "
                + "Create a new one: shorten https://example.com\n"), txt);
        assertEquals("", Files.readString(links));
        assertEquals("", Files.readString(notices));
    }
}
