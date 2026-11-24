package io.github.katehok.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.MutableClock;
import io.github.katehok.shortener.RecordingNotifier;
import io.github.katehok.shortener.RecordingNotifier.Notice;
import io.github.katehok.shortener.core.Notifier.Event;
import io.github.katehok.shortener.core.ShortenerException.Reason;
import io.github.katehok.shortener.infra.MemoryLinkRepository;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkServiceTest {

    private static final String URL = "https://www.baeldung.com/java-9-http-client";
    private static final Settings SETTINGS = new Settings(Duration.ofDays(1), 10, 1000, 2048, 10);

    private final MemoryLinkRepository repository = new MemoryLinkRepository();
    private final MutableClock clock = new MutableClock();
    private final RecordingNotifier notifier = new RecordingNotifier();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private LinkService service = service(CodeGenerator.random(7));

    private LinkService service(CodeGenerator generator) {
        return new LinkService(repository, generator, notifier, clock, SETTINGS);
    }

    private LinkService service(Iterator<String> codes) {
        return service(codes::next);
    }

    private void assertReason(Reason reason, Executable executable) {
        ShortenerException e = assertThrows(ShortenerException.class, executable);
        assertEquals(reason, e.getReason());
    }

    private ShortLink linkOf(UUID ownerId, String code) {
        return service.list(ownerId).stream()
                .filter(link -> link.getCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private void openTimes(String code, int times) {
        for (int i = 0; i < times; ++i) {
            service.open(code);
        }
    }

    @Test
    void shortenFillsLink() {
        ShortLink link = service.shorten(alice, URL, 5);
        assertEquals(alice, link.getOwnerId());
        assertEquals(URL, link.getUrl());
        assertEquals(5, link.getLimit());
        assertEquals(0, link.getClicks());
        assertEquals(clock.instant(), link.getCreatedAt());
        assertEquals(clock.instant().plus(SETTINGS.ttl()), link.getExpiresAt());
        assertTrue(repository.exists(link.getCode()));
    }

    @Test
    void shortenUsesDefaultLimit() {
        assertEquals(SETTINGS.defaultLimit(), service.shorten(alice, URL).getLimit());
    }

    @Test
    void sameUrlByDifferentUsersGetsDifferentCodes() {
        ShortLink first = service.shorten(alice, URL);
        ShortLink second = service.shorten(bob, URL);
        assertNotEquals(first.getCode(), second.getCode());
        assertEquals(URL, service.open(first.getCode()));
        assertEquals(URL, service.open(second.getCode()));
    }

    @Test
    void sameUrlTwiceBySameUserGetsTwoLinks() {
        ShortLink first = service.shorten(alice, URL);
        ShortLink second = service.shorten(alice, URL);
        assertNotEquals(first.getCode(), second.getCode());
        assertEquals(2, service.list(alice).size());
    }

    @Test
    void retriesWhenCodeIsTaken() {
        service = service(List.of("taken", "taken", "free").iterator());
        assertEquals("taken", service.shorten(alice, URL).getCode());
        assertEquals("free", service.shorten(bob, URL).getCode());
    }

    @Test
    void failsWhenNoFreeCode() {
        AtomicInteger calls = new AtomicInteger();
        service = service(() -> {
            calls.incrementAndGet();
            return "taken";
        });
        service.shorten(alice, URL);
        calls.set(0);
        assertReason(Reason.NO_FREE_CODE, () -> service.shorten(alice, URL));
        assertEquals(SETTINGS.codeAttempts(), calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "ftp://example.com", "http://", "javascript:alert(1)",
            "https://exa mple.com", "https://example.com:0", "https://example.com:65536", "https://example.com:99999/a",
            "https://пример.рф"})
    void rejectsInvalidUrl(String url) {
        assertReason(Reason.INVALID_URL, () -> service.shorten(alice, url));
    }

    @Test
    void rejectsNullUrl() {
        assertReason(Reason.INVALID_URL, () -> service.shorten(alice, null));
    }

    @Test
    void rejectsTooLongUrl() {
        String url = "https://example.com/" + "a".repeat(SETTINGS.maxUrlLength());
        assertReason(Reason.INVALID_URL, () -> service.shorten(alice, url));
    }

    @ParameterizedTest
    @ValueSource(strings = {URL, "http://localhost:8080/a?b=c#d", "https://example.com:1",
            "https://example.com:65535/a"})
    void acceptsValidUrl(String url) {
        assertEquals(url, service.shorten(alice, url).getUrl());
    }

    @Test
    void stripsUrlBeforeSaving() {
        ShortLink link = service.shorten(alice, " https://example.com ");
        assertEquals("https://example.com", link.getUrl());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 1001})
    void rejectsLimitOutOfRange(int limit) {
        assertReason(Reason.INVALID_LIMIT, () -> service.shorten(alice, URL, limit));
    }

    @Test
    void openCountsClicks() {
        String code = service.shorten(alice, URL, 5).getCode();
        assertEquals(URL, service.open(code));
        assertEquals(URL, service.open(code));
        assertEquals(2, linkOf(alice, code).getClicks());
        assertTrue(notifier.getNotices().isEmpty());
    }

    @Test
    void openBlocksExhaustedLinkAndNotifiesOnce() {
        String code = service.shorten(alice, URL, 2).getCode();
        openTimes(code, 2);
        assertReason(Reason.EXHAUSTED, () -> service.open(code));
        assertReason(Reason.EXHAUSTED, () -> service.open(code));
        assertEquals(2, linkOf(alice, code).getClicks());
        assertEquals(List.of(new Notice(code, Event.EXHAUSTED)), notifier.getNotices());
    }

    @Test
    void openExpiredLinkDeletesIt() {
        String code = service.shorten(alice, URL).getCode();
        clock.advance(SETTINGS.ttl().plusSeconds(1));
        assertReason(Reason.EXPIRED, () -> service.open(code));
        assertFalse(repository.exists(code));
        assertEquals(List.of(new Notice(code, Event.EXPIRED)), notifier.getNotices());
        assertReason(Reason.NOT_FOUND, () -> service.open(code));
    }

    @Test
    void openUnknownCodeFails() {
        assertReason(Reason.NOT_FOUND, () -> service.open("nope"));
    }

    @Test
    void purgeExpiredRemovesOnlyExpired() {
        String first = service.shorten(alice, URL).getCode();
        String second = service.shorten(bob, URL).getCode();
        clock.advance(Duration.ofHours(12));
        String fresh = service.shorten(alice, URL).getCode();
        clock.advance(Duration.ofHours(12));
        assertEquals(2, service.purgeExpired());
        assertFalse(repository.exists(first));
        assertFalse(repository.exists(second));
        assertTrue(repository.exists(fresh));
        List<Notice> expected = List.of(new Notice(first, Event.EXPIRED), new Notice(second, Event.EXPIRED));
        assertEquals(expected, notifier.getNotices());
        assertEquals(0, service.purgeExpired());
    }

    @Test
    void listReturnsOnlyOwnLinks() {
        String first = service.shorten(alice, URL).getCode();
        String second = service.shorten(alice, "https://example.com").getCode();
        service.shorten(bob, URL);
        List<String> codes = service.list(alice).stream().map(ShortLink::getCode).toList();
        assertEquals(List.of(first, second), codes);
    }

    @Test
    void listDropsExpiredLinks() {
        service.shorten(alice, URL);
        clock.advance(Duration.ofHours(12));
        String fresh = service.shorten(alice, URL).getCode();
        clock.advance(Duration.ofHours(12));
        List<String> codes = service.list(alice).stream().map(ShortLink::getCode).toList();
        assertEquals(List.of(fresh), codes);
    }

    @Test
    void changeLimitByOwner() {
        String code = service.shorten(alice, URL, 5).getCode();
        assertEquals(20, service.changeLimit(alice, code, 20).getLimit());
        assertEquals(20, linkOf(alice, code).getLimit());
    }

    @Test
    void changeLimitUnblocksExhaustedLink() {
        String code = service.shorten(alice, URL, 1).getCode();
        service.open(code);
        assertReason(Reason.EXHAUSTED, () -> service.open(code));
        service.changeLimit(alice, code, 3);
        assertEquals(URL, service.open(code));
        assertEquals(2, linkOf(alice, code).getClicks());
    }

    @Test
    void changeLimitByOtherUserFails() {
        String code = service.shorten(alice, URL).getCode();
        assertReason(Reason.NOT_OWNER, () -> service.changeLimit(bob, code, 20));
        assertReason(Reason.NOT_OWNER, () -> service.changeLimit(null, code, 20));
        assertEquals(SETTINGS.defaultLimit(), linkOf(alice, code).getLimit());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void changeLimitNotAboveClicksFails(int limit) {
        String code = service.shorten(alice, URL, 5).getCode();
        openTimes(code, 2);
        assertReason(Reason.INVALID_LIMIT, () -> service.changeLimit(alice, code, limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 1001})
    void changeLimitOutOfRangeFails(int limit) {
        String code = service.shorten(alice, URL).getCode();
        assertReason(Reason.INVALID_LIMIT, () -> service.changeLimit(alice, code, limit));
    }

    @Test
    void changeUrlByOwner() {
        String code = service.shorten(alice, URL).getCode();
        service.changeUrl(alice, code, " https://example.com ");
        assertEquals("https://example.com", service.open(code));
    }

    @Test
    void changeUrlToInvalidFails() {
        String code = service.shorten(alice, URL).getCode();
        assertReason(Reason.INVALID_URL, () -> service.changeUrl(alice, code, "abc"));
        assertEquals(URL, linkOf(alice, code).getUrl());
    }

    @Test
    void changeUrlByOtherUserFails() {
        String code = service.shorten(alice, URL).getCode();
        assertReason(Reason.NOT_OWNER, () -> service.changeUrl(bob, code, "https://example.com"));
        assertEquals(URL, linkOf(alice, code).getUrl());
    }

    @Test
    void deleteByOwner() {
        String code = service.shorten(alice, URL).getCode();
        service.delete(alice, code);
        assertFalse(repository.exists(code));
        assertReason(Reason.NOT_FOUND, () -> service.open(code));
    }

    @Test
    void deleteByOtherUserFails() {
        String code = service.shorten(alice, URL).getCode();
        assertReason(Reason.NOT_OWNER, () -> service.delete(bob, code));
        assertTrue(repository.exists(code));
    }

    @Test
    void deleteUnknownCodeFails() {
        assertReason(Reason.NOT_FOUND, () -> service.delete(alice, "nope"));
    }

    @Test
    void openAtExpiryMomentFails() {
        String code = service.shorten(alice, URL).getCode();
        clock.advance(SETTINGS.ttl());
        assertReason(Reason.EXPIRED, () -> service.open(code));
        assertFalse(repository.exists(code));
    }
}
