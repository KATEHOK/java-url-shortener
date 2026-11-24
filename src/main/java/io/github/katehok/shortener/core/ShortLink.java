package io.github.katehok.shortener.core;

import java.time.Instant;
import java.util.UUID;

public class ShortLink {

    public enum Status {
        ACTIVE,
        EXHAUSTED,
        EXPIRED,
    }

    private final String code;
    private final UUID ownerId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private String url;
    private int limit;
    private int clicks;

    public ShortLink(String code, String url, UUID ownerId, Instant createdAt, Instant expiresAt, int limit,
            int clicks) {
        this.code = code;
        this.url = url;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.limit = limit;
        this.clicks = clicks;
    }

    public String getCode() {
        return code;
    }

    public String getUrl() {
        return url;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getLimit() {
        return limit;
    }

    public int getClicks() {
        return clicks;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId.equals(userId);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isExhausted() {
        return clicks >= limit;
    }

    public Status getStatus(Instant now) {
        if (isExpired(now)) return Status.EXPIRED;
        if (isExhausted()) return Status.EXHAUSTED;
        return Status.ACTIVE;
    }

    // click() и сеттеры package-private - менять ссылку может только сервис из core
    void click() {
        ++clicks;
    }

    void setUrl(String url) {
        this.url = url;
    }

    void setLimit(int limit) {
        this.limit = limit;
    }
}
