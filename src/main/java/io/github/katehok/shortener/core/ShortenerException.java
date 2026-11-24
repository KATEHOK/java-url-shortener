package io.github.katehok.shortener.core;

public class ShortenerException extends RuntimeException {

    public enum Reason {
        INVALID_URL,
        INVALID_LIMIT,
        NOT_FOUND,
        NOT_OWNER,
        EXPIRED,
        EXHAUSTED,
        NO_FREE_CODE,
    }

    private final Reason reason;

    public ShortenerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
