package io.github.katehok.shortener.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.MutableClock;
import io.github.katehok.shortener.RecordingNotifier;
import io.github.katehok.shortener.RecordingNotifier.Notice;
import io.github.katehok.shortener.core.CodeGenerator;
import io.github.katehok.shortener.core.LinkService;
import io.github.katehok.shortener.core.Notifier.Event;
import io.github.katehok.shortener.core.Settings;
import io.github.katehok.shortener.core.ShortLink;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CleanupSchedulerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Settings SETTINGS = new Settings(Duration.ofHours(1), 10, 1000, 2048, 10);
    private static final Duration PERIOD = Duration.ofMillis(20);
    private static final long TIMEOUT_MS = 2000;

    private final MutableClock clock = new MutableClock();
    private final RecordingNotifier notifier = new RecordingNotifier();

    private boolean waitForNotice() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!notifier.getNotices().isEmpty()) return true;
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void removesExpiredLinks() throws InterruptedException {
        MemoryLinkRepository repository = new MemoryLinkRepository();
        LinkService service = new LinkService(repository, CodeGenerator.random(7), notifier, clock, SETTINGS);
        String code = service.shorten(ALICE, "https://example.com").getCode();
        clock.advance(Duration.ofHours(2));

        try (CleanupScheduler scheduler = new CleanupScheduler(service, PERIOD)) {
            scheduler.start();
            assertTrue(waitForNotice());
        }
        assertFalse(repository.exists(code));
        assertEquals(List.of(new Notice(code, Event.EXPIRED)), notifier.getNotices());
    }

    @Test
    void keepsRunningAfterFailure() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        MemoryLinkRepository repository = new MemoryLinkRepository() {
            @Override
            public List<ShortLink> findAll() {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("boom");
                return super.findAll();
            }
        };
        LinkService service = new LinkService(repository, CodeGenerator.random(7), notifier, clock, SETTINGS);
        String code = service.shorten(ALICE, "https://example.com").getCode();
        clock.advance(Duration.ofHours(2));

        try (CleanupScheduler scheduler = new CleanupScheduler(service, PERIOD)) {
            scheduler.start();
            assertTrue(waitForNotice());
        }
        assertFalse(repository.exists(code));
        assertTrue(calls.get() >= 2);
    }
}
