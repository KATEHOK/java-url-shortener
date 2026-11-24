package io.github.katehok.shortener.core;

public interface Notifier {

    enum Event {
        EXHAUSTED,
        EXPIRED,
    }

    void send(ShortLink link, Event event);
}
