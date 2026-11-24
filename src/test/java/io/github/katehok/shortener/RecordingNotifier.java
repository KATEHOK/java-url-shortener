package io.github.katehok.shortener;

import io.github.katehok.shortener.core.Notifier;
import io.github.katehok.shortener.core.ShortLink;
import java.util.ArrayList;
import java.util.List;

public class RecordingNotifier implements Notifier {

    public record Notice(String code, Event event) {
    }

    private final List<Notice> notices = new ArrayList<>();

    @Override
    public synchronized void send(ShortLink link, Event event) {
        notices.add(new Notice(link.getCode(), event));
    }

    public synchronized List<Notice> getNotices() {
        return List.copyOf(notices);
    }
}
