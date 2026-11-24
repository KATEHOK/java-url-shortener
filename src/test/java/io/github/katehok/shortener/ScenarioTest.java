package io.github.katehok.shortener;

import static io.github.katehok.shortener.CliRunner.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.cli.NoticeBox;
import io.github.katehok.shortener.core.CodeGenerator;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.Settings;
import io.github.katehok.shortener.infra.CleanupScheduler;
import io.github.katehok.shortener.infra.FileLinkRepository;
import io.github.katehok.shortener.infra.FileNoticeStore;
import io.github.katehok.shortener.infra.FileUserStore;
import io.github.katehok.shortener.infra.MemoryLinkRepository;
import io.github.katehok.shortener.infra.MemoryNoticeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScenarioTest {

    private static final String URL = "https://example.com/very/long/path?id=42";
    private static final String UNAVAILABLE = "The link is unavailable now. Create a new one: shorten <url>";
    private static final Duration PERIOD = Duration.ofMillis(20);
    private static final long TIMEOUT_MS = 2000;

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();
    private final MemoryUserStore userStore = new MemoryUserStore();
    private final List<String> opened = new ArrayList<>();
    private final MemoryLinkRepository repository = new MemoryLinkRepository();
    private LinkService service;
    private CliRunner runner;

    private void start(Duration ttl, CodeGenerator generator, String script) {
        Settings settings = new Settings(ttl, 10, 1000, 2048, 10);
        NoticeBox notices = new NoticeBox(PREFIX, new MemoryNoticeStore());
        service = new LinkService(repository, generator, notices, clock, settings);
        runner = new CliRunner(service, userStore, url -> opened.add(url), notices, clock, script);
    }

    private void start(Duration ttl) {
        start(ttl, CodeGenerator.random(6), "");
    }

    private String execute(String input) {
        return runner.execute(input);
    }

    private String shorten(String args) {
        return runner.shorten(args);
    }

    private UUID currentUser() {
        return userStore.load().orElseThrow();
    }

    private boolean exists(String link) {
        synchronized (service) {
            return repository.exists(link.substring(PREFIX.length()));
        }
    }

    private boolean waitUntilGone(String link) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!exists(link)) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private CliRunner restart(String script) throws IOException {
        Settings settings = new Settings(Duration.ofDays(1), 10, 1000, 2048, 10);
        NoticeBox notices = new NoticeBox(PREFIX, new FileNoticeStore(dir.resolve("data/notices.tsv")));
        FileLinkRepository links = new FileLinkRepository(dir.resolve("data/links.tsv"));
        LinkService restarted = new LinkService(links, CodeGenerator.random(7), notices, clock, settings);
        FileUserStore users = new FileUserStore(dir.resolve("data/user.id"));
        return new CliRunner(restarted, users, url -> opened.add(url), notices, clock, script);
    }

    private void checkTtl(Duration ttl) {
        start(ttl);
        String link = shorten(URL);

        clock.advance(ttl.minusMinutes(1));
        assertTrue(execute(link).contains("Opening " + URL));

        clock.advance(Duration.ofMinutes(1));
        String txt = execute(link);
        assertTrue(txt.contains("Notice: Your link " + link + " expired and was deleted. "
                + "Create a new one: shorten " + URL), txt);
        assertTrue(txt.contains("Oops: Link expired and was deleted. " + UNAVAILABLE), txt);
        assertEquals(List.of(URL), opened);

        assertTrue(execute(link).contains("Oops: Link not found"));
        assertTrue(execute("list").contains("You have no links yet"));
    }

    @Test
    void sameUrlGetsDifferentLinksForDifferentUsers() {
        start(Duration.ofDays(1));
        String first = shorten(URL);
        UUID firstUser = currentUser();
        execute("user new");
        String second = shorten(URL);
        assertNotEquals(firstUser, currentUser());
        assertNotEquals(first, second);

        execute(first);
        execute(second);
        assertEquals(List.of(URL, URL), opened);
        assertTrue(execute("list").contains(second));
        assertFalse(execute("list").contains(first));
    }

    @Test
    void linkIsBlockedAfterLimitAndOwnerIsNotified() {
        start(Duration.ofDays(1));
        String link = shorten(URL + " 3");
        UUID owner = currentUser();
        execute("user new");

        assertTrue(execute(link).contains("Opening " + URL));
        assertTrue(execute(link).contains("Opening " + URL));
        String txt = execute(link);
        assertTrue(txt.contains("Opening " + URL), txt);
        assertFalse(txt.contains("Notice"), txt);
        assertFalse(txt.contains(owner.toString()), txt);

        txt = execute(link);
        assertTrue(txt.contains("Oops: Link reached its clicks limit (3)"), txt);
        assertFalse(txt.contains("Opening"), txt);
        assertFalse(txt.contains(owner.toString()), txt);
        assertEquals(3, opened.size());

        txt = execute("user " + owner);
        assertTrue(txt.contains("Notice: Your link " + link + " reached its clicks limit (3) and is blocked. "
                + "Raise it with: edit " + link + " limit <n>, or create a new one: shorten " + URL), txt);
        assertTrue(execute("list").contains("clicks 3/3 | blocked"));

        execute("edit " + link + " limit 4");
        assertTrue(execute(link).contains("Opening " + URL));
        assertEquals(4, opened.size());
    }

    @Test
    void linkIsDeletedAfterOneHourTtl() {
        checkTtl(Duration.ofHours(1));
    }

    @Test
    void linkIsDeletedAfterOneDayTtl() {
        checkTtl(Duration.ofDays(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1H", "P1D"})
    void schedulerDeletesUnopenedLinkAndOwnerIsNotified(String ttlTxt) throws InterruptedException {
        Duration ttl = Duration.parse(ttlTxt);
        start(ttl);
        String link = shorten(URL);
        UUID owner = currentUser();
        execute("user new");

        clock.advance(ttl.minusMinutes(1));
        try (CleanupScheduler scheduler = new CleanupScheduler(service, PERIOD)) {
            scheduler.start();
            Thread.sleep(PERIOD.toMillis() * 5);
            assertTrue(exists(link));
            clock.advance(Duration.ofMinutes(1));
            assertTrue(waitUntilGone(link));
        }

        String txt = execute("list");
        assertFalse(txt.contains("Notice"), txt);
        assertFalse(txt.contains(owner.toString()), txt);
        txt = execute("user " + owner);
        assertTrue(txt.contains("Notice: Your link " + link + " expired and was deleted. "
                + "Create a new one: shorten " + URL), txt);
        assertTrue(execute("list").contains("You have no links yet"));
        assertTrue(opened.isEmpty());
    }

    @Test
    void linksAndUserSurviveRestart() throws IOException {
        CliRunner first = restart("");
        String link = first.shorten(URL + " 5");
        first.execute(link);
        UUID owner = new FileUserStore(dir.resolve("data/user.id")).load().orElseThrow();

        String txt = restart("list\n" + link + "\nexit\n").run();
        assertTrue(txt.startsWith("Hi, " + owner + "!"), txt);
        assertTrue(txt.contains(link + " -> " + URL + " | clicks 1/5 | active"), txt);
        assertTrue(txt.contains("Opening " + URL), txt);
        assertEquals(List.of(URL, URL), opened);
        assertTrue(restart("list\n").run().contains(link + " -> " + URL + " | clicks 2/5"));
    }

    @Test
    void noticeForAnotherUserSurvivesRestart() throws IOException {
        CliRunner first = restart("");
        String link = first.shorten(URL + " 1");
        UUID owner = new FileUserStore(dir.resolve("data/user.id")).load().orElseThrow();
        first.execute("user new");
        String txt = first.execute(link);
        assertTrue(txt.contains("Opening " + URL), txt);
        assertFalse(txt.contains("Notice"), txt);

        txt = restart("list\nuser " + owner + "\nlist\nexit\n").run();
        String notice = "Notice: Your link " + link + " reached its clicks limit (1) and is blocked";
        assertTrue(txt.contains("Switched to user " + owner), txt);
        assertTrue(txt.indexOf("Switched to user " + owner) < txt.indexOf(notice), txt);
        assertEquals(txt.indexOf(notice), txt.lastIndexOf(notice), txt);
        assertFalse(restart("user " + owner + "\n").run().contains("Notice"));
    }

    @Test
    void expiredLinkIsDeletedByListToo() {
        start(Duration.ofHours(1));
        String link = shorten(URL);
        clock.advance(Duration.ofHours(1));
        String txt = execute("list");
        assertTrue(txt.contains(link + " expired and was deleted"), txt);
        assertTrue(txt.contains("You have no links yet"), txt);
    }

    @Test
    void unavailableLinkSuggestsNewOne() {
        start(Duration.ofHours(1));
        String exhausted = shorten(URL + " 1");
        String expiring = shorten(URL);
        execute(exhausted);
        assertTrue(execute(exhausted).contains(UNAVAILABLE));
        clock.advance(Duration.ofHours(2));
        assertTrue(execute(expiring).contains(UNAVAILABLE));
    }

    @Test
    void fullSessionThroughRunLoop() {
        AtomicInteger counter = new AtomicInteger();
        String script = String.join("\n",
                "help",
                "",
                "shorten " + URL + " 1",
                PREFIX + "abc1",
                "open https://" + PREFIX + "abc1",
                "list",
                "bogus",
                "exit",
                "list");
        start(Duration.ofDays(1), () -> "abc" + counter.incrementAndGet(), script);
        String txt = runner.run();

        assertTrue(txt.startsWith("Hi! This is a console URL shortener. Type help to see commands\n"), txt);
        assertTrue(txt.contains("Short link: " + PREFIX + "abc1"), txt);
        assertTrue(txt.contains("You are a new user: " + currentUser()), txt);
        assertTrue(txt.contains("Opening " + URL), txt);
        assertTrue(txt.contains("reached its clicks limit (1) and is blocked"), txt);
        assertTrue(txt.contains("Oops: Link reached its clicks limit (1). " + UNAVAILABLE), txt);
        assertTrue(txt.contains(PREFIX + "abc1 -> " + URL + " | clicks 1/1 | blocked"), txt);
        assertTrue(txt.contains("Unknown command. Type help to see commands"), txt);
        assertTrue(txt.endsWith("Bye\n"), txt);
        assertEquals(8, txt.split("shortener@java \\$ ", -1).length - 1);
        assertEquals(List.of(URL), opened);
    }
}
