package io.github.katehok.shortener.cli;

import static io.github.katehok.shortener.CliRunner.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.CliRunner;
import io.github.katehok.shortener.MemoryUserStore;
import io.github.katehok.shortener.MutableClock;
import io.github.katehok.shortener.core.Browser;
import io.github.katehok.shortener.core.CodeGenerator;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.Settings;
import io.github.katehok.shortener.core.ShortLink;
import io.github.katehok.shortener.core.UserStore;
import io.github.katehok.shortener.infra.MemoryLinkRepository;
import io.github.katehok.shortener.infra.MemoryNoticeStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliTest {

    private static final String URL = "https://example.com/Some/Long/Page?q=1";
    private static final String OTHER_URL = "https://example.org/other";
    private static final String OTHER_USER = "123e4567-e89b-12d3-a456-426614174000";
    private static final Settings SETTINGS = new Settings(Duration.ofDays(1), 10, 1000, 2048, 10);

    private final MutableClock clock = new MutableClock();
    private final MemoryUserStore userStore = new MemoryUserStore();
    private final List<String> opened = new ArrayList<>();
    private final NoticeBox notices = new NoticeBox(PREFIX, new MemoryNoticeStore());
    private boolean browserWorks = true;
    private LinkService service;
    private Browser browser;
    private CliRunner runner;

    @BeforeEach
    void setUp() {
        browser = url -> {
            opened.add(url);
            return browserWorks;
        };
        start(CodeGenerator.random(6));
    }

    private void start(CodeGenerator generator) {
        service = new LinkService(new MemoryLinkRepository(), generator, notices, clock, SETTINGS);
        runner = new CliRunner(service, userStore, browser, notices, clock);
    }

    private String execute(String input) {
        return runner.execute(input);
    }

    private String shorten(String url) {
        return runner.shorten(url);
    }

    private String shorten() {
        return shorten(URL);
    }

    @Test
    void helpListsAllCommands() {
        String txt = execute("help");
        for (String cmd : List.of("shorten", "open", "list", "edit", "delete", "user", "user new", "help", "exit")) {
            assertTrue(txt.contains(cmd), "no " + cmd + " in help");
        }
        assertEquals(txt, execute("h"));
        assertFalse(txt.contains("e.g. list"), txt);
        assertFalse(txt.contains("e.g. user new"), txt);
        assertTrue(txt.contains("e.g. edit " + PREFIX + "abc123 limit 20"), txt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ten", "2147483648", "1.5"})
    void notNumberLimitPrintsRange(String limit) {
        String txt = execute("shorten " + URL + " " + limit);
        assertEquals("Oops: Limit must be a whole number from 1 to 1000. If the URL has spaces, encode them as %20\n",
                txt);
        assertTrue(userStore.load().isEmpty());
        String link = shorten();
        txt = execute("edit " + link + " limit " + limit);
        assertEquals("Oops: Limit must be a whole number from 1 to 1000\n", txt);
    }

    @Test
    void minIntLimitIsTreatedAsNumber() {
        String txt = execute("shorten " + URL + " -2147483648");
        assertTrue(txt.contains("Oops: Limit must be a whole number from 1 to 1000. Try another"), txt);
    }

    @Test
    void urlWithSpacesSuggestsEncoding() {
        String txt = execute("shorten https://example.com/my page 5");
        assertEquals("Oops: The URL must not contain spaces. Encode them as %20\n", txt);
        assertTrue(execute("shorten https://example.com/my page").contains("encode them as %20"));
        assertTrue(userStore.load().isEmpty());
    }

    @Test
    void listRoundsTimeLeftUp() {
        shorten();
        clock.advance(Duration.ofSeconds(30));
        assertTrue(execute("list").contains("| 24h 0m left"));
        clock.advance(Duration.ofSeconds(60));
        assertTrue(execute("list").contains("| 23h 59m left"));
        clock.advance(Duration.ofHours(24));
        assertTrue(execute("list").contains("You have no links yet"));
    }

    @Test
    void shortenCreatesUserAndPrintsLink() {
        String txt = execute("shorten " + URL);
        assertTrue(userStore.load().isPresent());
        String userId = userStore.load().get().toString();
        assertTrue(txt.contains("You are a new user: " + userId), txt);
        assertTrue(txt.contains("Short link: " + PREFIX), txt);
        assertTrue(txt.contains("Clicks limit: 10"), txt);
        assertTrue(txt.contains("Expires at: 2026-11-19 12:00"), txt);
    }

    @Test
    void secondShortenKeepsUser() {
        shorten();
        UUID userId = userStore.load().orElseThrow();
        String txt = execute("shorten " + OTHER_URL + " 3");
        assertFalse(txt.contains("new user"), txt);
        assertTrue(txt.contains("Clicks limit: 3"), txt);
        assertEquals(userId, userStore.load().orElseThrow());
    }

    @Test
    void commandWordIsCaseInsensitive() {
        String link = shorten();
        execute("OPEN " + link);
        assertEquals(List.of(URL), opened);
    }

    @Test
    void shortenWithInvalidUrlPrintsError() {
        String txt = execute("shorten example.com");
        assertTrue(txt.contains("Oops: Invalid URL: example.com"), txt);
        assertTrue(txt.contains("https://example.com/page"), txt);
        assertTrue(userStore.load().isEmpty());
    }

    @Test
    void shortenWithZeroLimitPrintsError() {
        String txt = execute("shorten " + URL + " 0");
        assertTrue(txt.contains("Oops: Limit must be a whole number from 1 to 1000"), txt);
    }

    @Test
    void shortenWithoutUrlPrintsUsage() {
        assertTrue(execute("shorten").contains("Usage: shorten <url> [limit]"));
    }

    @Test
    void openViaFullLink() {
        String link = shorten();
        String txt = execute("open https://" + link);
        assertTrue(txt.contains("Opening " + URL), txt);
        execute("open http://" + link);
        execute("open " + link);
        assertEquals(List.of(URL, URL, URL), opened);
    }

    @Test
    void openViaBareCode() {
        String link = shorten();
        execute("open " + link.substring(PREFIX.length()));
        assertEquals(List.of(URL), opened);
    }

    @Test
    void codeIsCaseSensitive() {
        start(() -> "AbC123");
        assertEquals(PREFIX + "AbC123", shorten());
        String txt = execute("open " + PREFIX + "abc123");
        assertTrue(txt.contains("Oops: Link not found"), txt);
        execute(PREFIX + "AbC123");
        assertEquals(List.of(URL), opened);
    }

    @Test
    void typingLinkOpensIt() {
        String link = shorten();
        execute(link);
        execute("https://" + link);
        assertEquals(List.of(URL, URL), opened);
    }

    @Test
    void openUnknownLinkSuggestsShorten() {
        String txt = execute("open " + PREFIX + "nope");
        assertTrue(txt.contains("Oops: Link not found"), txt);
        assertTrue(txt.contains("shorten <url>"), txt);
    }

    @Test
    void browserFailurePrintsManualHint() {
        String link = shorten();
        browserWorks = false;
        String txt = execute(link);
        assertTrue(txt.contains("Open the link manually: " + URL), txt);
    }

    @Test
    void listShowsOnlyOwnLinks() {
        shorten(OTHER_URL);
        execute("user new");
        String link = shorten();
        execute(link);
        String txt = execute("list");
        assertTrue(txt.contains(link + " -> " + URL), txt);
        assertTrue(txt.contains("clicks 1/10"), txt);
        assertTrue(txt.contains("active"), txt);
        assertTrue(txt.contains("24h 0m left"), txt);
        assertFalse(txt.contains(OTHER_URL), txt);
    }

    @Test
    void listWithoutLinksPrintsHint() {
        assertTrue(execute("list").contains("You have no links yet"));
        execute("user new");
        assertTrue(execute("list").contains("You have no links yet"));
    }

    @Test
    void editLimitByOwner() {
        String link = shorten();
        String txt = execute("edit " + link + " limit 20");
        assertTrue(txt.contains("Clicks limit of " + link + " is now 20"), txt);
        assertTrue(execute("list").contains("clicks 0/20"));
    }

    @Test
    void editUrlByOwner() {
        String link = shorten();
        String txt = execute("edit " + link + " url " + OTHER_URL);
        assertTrue(txt.contains(link + " now leads to " + OTHER_URL), txt);
        execute(link);
        assertEquals(List.of(OTHER_URL), opened);
    }

    @Test
    void editByAnotherUserRefused() {
        String link = shorten();
        execute("user " + OTHER_USER);
        String txt = execute("edit " + link + " limit 20");
        assertTrue(txt.contains("Oops: Only the owner can change or delete this link"), txt);
        assertTrue(txt.contains("Type user"), txt);
    }

    @Test
    void editWithBadArgumentsPrintsUsage() {
        String link = shorten();
        assertTrue(execute("edit " + link).contains("Usage: edit"));
        assertTrue(execute("edit " + link + " color red").contains("Usage: edit"));
    }

    @Test
    void deleteByNonOwnerRefused() {
        String link = shorten();
        execute("user " + OTHER_USER);
        String txt = execute("delete " + link);
        assertTrue(txt.contains("Oops: Only the owner"), txt);
        execute(link);
        assertEquals(List.of(URL), opened);
    }

    @Test
    void deleteWithoutUserRefused() {
        String code = service.shorten(UUID.fromString(OTHER_USER), URL).getCode();
        String txt = execute("delete " + PREFIX + code);
        assertTrue(txt.contains("Oops: Only the owner"), txt);
        assertTrue(execute("edit " + code + " limit 5").contains("Oops: Only the owner"));
    }

    @Test
    void deleteByOwner() {
        String link = shorten();
        String txt = execute("delete " + link);
        assertTrue(txt.contains("Deleted " + link), txt);
        assertTrue(execute(link).contains("Oops: Link not found"));
        assertTrue(opened.isEmpty());
    }

    @Test
    void userShowsCurrentUuid() {
        assertTrue(execute("user").contains("You have no UUID yet"));
        shorten();
        assertTrue(execute("user").contains("You are " + userStore.load().orElseThrow()));
    }

    @Test
    void userNewChangesUuid() {
        shorten();
        UUID oldId = userStore.load().orElseThrow();
        String txt = execute("user new");
        UUID newId = userStore.load().orElseThrow();
        assertNotEquals(oldId, newId);
        assertTrue(txt.contains("You are a new user: " + newId), txt);
    }

    @Test
    void userSwitchesToGivenUuid() {
        String txt = execute("user " + OTHER_USER);
        assertTrue(txt.contains("Switched to user " + OTHER_USER), txt);
        assertEquals(UUID.fromString(OTHER_USER), userStore.load().orElseThrow());
    }

    @Test
    void userRejectsInvalidUuid() {
        String txt = execute("user 1-2-3-4-5");
        assertTrue(txt.contains("Invalid UUID"), txt);
        assertTrue(userStore.load().isEmpty());
    }

    @Test
    void unknownCommand() {
        assertTrue(execute("fly away").contains("Unknown command. Type help to see commands"));
        assertTrue(execute("https://example.com").contains("Unknown command"));
    }

    @Test
    void exitReturnsFalse() {
        Cli cli = runner.getCli();
        assertFalse(cli.execute("exit"));
        assertFalse(cli.execute("QUIT"));
        assertTrue(cli.execute("help"));
        assertTrue(cli.execute("   "));
    }

    @Test
    void runGreetsStoredUserAndStopsAtEndOfInput() {
        userStore.save(UUID.fromString(OTHER_USER));
        String script = "user\n\nlist\n";
        String txt = new CliRunner(service, userStore, browser, notices, clock, script).run();
        assertTrue(txt.startsWith("Hi, " + OTHER_USER + "!"), txt);
        assertTrue(txt.contains("shortener@java $ You are " + OTHER_USER), txt);
        assertTrue(txt.contains("You have no links yet"), txt);
        assertTrue(txt.endsWith("Bye\n"), txt);
    }

    @Test
    void noticeWaitsUntilOwnerComesBack() {
        String link = shorten(URL + " 1");
        UUID owner = userStore.load().orElseThrow();
        execute("user " + OTHER_USER);
        String txt = execute(link);
        assertTrue(txt.contains("Opening " + URL), txt);
        assertFalse(txt.contains("Notice"), txt);
        assertFalse(txt.contains(owner.toString()), txt);
        assertFalse(execute("list").contains("Notice"));

        txt = execute("user " + owner);
        assertTrue(txt.contains("Notice: Your link " + link + " reached its clicks limit (1) and is blocked"), txt);
        assertFalse(txt.contains("Notice for"), txt);
        assertFalse(execute("list").contains("Notice"));
    }

    @Test
    void runPrintsPendingNoticesAfterGreeting() {
        UUID owner = UUID.fromString(OTHER_USER);
        String code = service.shorten(owner, URL, 1).getCode();
        service.open(code);
        userStore.save(owner);
        String txt = new CliRunner(service, userStore, browser, notices, clock).run();
        String greeting = "Hi, " + OTHER_USER + "! Type help to see commands\n";
        assertTrue(txt.startsWith(greeting + "Notice: Your link " + PREFIX + code), txt);
    }

    @Test
    void runContinuesWhenUserFileIsBroken() {
        UserStore broken = new UserStore() {
            @Override
            public Optional<UUID> load() {
                throw new UncheckedIOException("Couldn't read data/user.id", new IOException("Is a directory"));
            }

            @Override
            public void save(UUID userId) {
            }
        };
        String script = "user\nshorten " + URL + "\n";
        String txt = new CliRunner(service, broken, browser, notices, clock, script).run();
        String warning = "Couldn't read data/user.id (Is a directory). Starting without a user\n";
        assertTrue(txt.startsWith(warning + "Hi! This is a console URL shortener"), txt);
        assertTrue(txt.contains("You have no UUID yet"), txt);
        assertTrue(txt.contains("Short link: " + PREFIX), txt);
        assertTrue(txt.endsWith("Bye\n"), txt);
    }

    private void useUserStoreThatCannotSave() {
        UserStore readOnly = new UserStore() {
            @Override
            public Optional<UUID> load() {
                return Optional.empty();
            }

            @Override
            public void save(UUID userId) {
                throw new UncheckedIOException("Couldn't write data/user.id", new IOException("Permission denied"));
            }
        };
        runner = new CliRunner(service, readOnly, browser, notices, clock);
    }

    @Test
    void failedUserSaveStillShowsLink() {
        useUserStoreThatCannotSave();
        String txt = execute("shorten " + URL);
        String userId = execute("user").substring("You are ".length()).strip();
        String[] lines = txt.split("\n");
        assertTrue(lines[0].startsWith("Short link: " + PREFIX), txt);
        assertEquals("Clicks limit: 10", lines[1]);
        assertEquals("You are a new user: " + userId, lines[3]);
        assertEquals("Couldn't save your UUID: Couldn't write data/user.id (Permission denied). "
                + "It stays current until exit. Keep it yourself: user " + userId, lines[5]);
        assertTrue(execute("list").contains(" -> " + URL), txt);
    }

    @Test
    void failedUserSaveStillSwitchesUser() {
        useUserStoreThatCannotSave();
        String txt = execute("user " + OTHER_USER);
        assertTrue(txt.startsWith("Switched to user " + OTHER_USER + "\nCouldn't save your UUID"), txt);
        assertTrue(txt.contains("Keep it yourself: user " + OTHER_USER), txt);
        assertEquals("You are " + OTHER_USER + "\n", execute("user"));

        txt = execute("user new");
        assertTrue(txt.contains("You are a new user"), txt);
        assertTrue(txt.contains("Couldn't save your UUID"), txt);
        assertNotEquals("You are " + OTHER_USER + "\n", execute("user"));
    }

    @Test
    void ioErrorShowsCause() {
        MemoryLinkRepository broken = new MemoryLinkRepository() {
            @Override
            public void save(ShortLink link) {
                IOException cause = new AccessDeniedException("/x/links.tsv.tmp");
                throw new UncheckedIOException("Couldn't write /x/links.tsv", cause);
            }
        };
        service = new LinkService(broken, CodeGenerator.random(6), notices, clock, SETTINGS);
        runner = new CliRunner(service, userStore, browser, notices, clock);
        String txt = execute("shorten " + URL);
        assertEquals("Couldn't save data: Couldn't write /x/links.tsv (Permission denied)\n", txt);
    }

    @Test
    void unexpectedErrorDoesNotStopCli() {
        String link = shorten();
        browser = url -> {
            throw new IllegalStateException("boom");
        };
        runner = new CliRunner(service, userStore, browser, notices, clock);
        assertTrue(execute(link).contains("Unexpected error: boom"));
        assertTrue(runner.getCli().execute("list"));
    }
}
