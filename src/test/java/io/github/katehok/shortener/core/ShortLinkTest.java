package io.github.katehok.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.katehok.shortener.core.ShortLink.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShortLinkTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofDays(1));

    private final UUID owner = UUID.randomUUID();

    private ShortLink link(int limit, int clicks) {
        return new ShortLink("abc1234", "https://example.com", owner, NOW, EXPIRES_AT, limit, clicks);
    }

    private ShortLink link() {
        return link(3, 0);
    }

    @Test
    void newLinkIsActive() {
        assertEquals(Status.ACTIVE, link().getStatus(NOW));
    }

    @Test
    void becomesExhaustedWhenClicksReachLimit() {
        ShortLink link = link(2, 0);
        link.click();
        assertEquals(Status.ACTIVE, link.getStatus(NOW));
        link.click();
        assertTrue(link.isExhausted());
        assertEquals(Status.EXHAUSTED, link.getStatus(NOW));
    }

    @Test
    void isExhaustedWhenCreatedWithClicksEqualToLimit() {
        assertEquals(Status.EXHAUSTED, link(5, 5).getStatus(NOW));
    }

    @Test
    void isActiveRightBeforeExpiresAt() {
        ShortLink link = link();
        Instant before = EXPIRES_AT.minusNanos(1);
        assertFalse(link.isExpired(before));
        assertEquals(Status.ACTIVE, link.getStatus(before));
    }

    @Test
    void isExpiredExactlyAtExpiresAt() {
        ShortLink link = link();
        assertTrue(link.isExpired(EXPIRES_AT));
        assertEquals(Status.EXPIRED, link.getStatus(EXPIRES_AT));
    }

    @Test
    void isExpiredAfterExpiresAt() {
        assertEquals(Status.EXPIRED, link().getStatus(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    void expiredWinsOverExhausted() {
        assertEquals(Status.EXPIRED, link(1, 1).getStatus(EXPIRES_AT));
    }

    @Test
    void isOwnedByOwnerOnly() {
        ShortLink link = link();
        assertTrue(link.isOwnedBy(owner));
        assertFalse(link.isOwnedBy(UUID.randomUUID()));
        assertFalse(link.isOwnedBy(null));
    }
}
