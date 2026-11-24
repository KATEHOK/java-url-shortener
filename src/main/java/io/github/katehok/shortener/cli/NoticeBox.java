package io.github.katehok.shortener.cli;

import io.github.katehok.shortener.core.NoticeStore;
import io.github.katehok.shortener.core.Notifier;
import io.github.katehok.shortener.core.ShortLink;
import java.util.List;
import java.util.UUID;

public class NoticeBox implements Notifier {

    private final String linkPrefix;
    private final NoticeStore store;

    public NoticeBox(String linkPrefix, NoticeStore store) {
        this.linkPrefix = linkPrefix;
        this.store = store;
    }

    @Override
    public synchronized void send(ShortLink link, Event event) {
        String shortLink = linkPrefix + link.getCode();
        String txt;
        switch (event) {
            case EXHAUSTED:
                txt = "Your link " + shortLink + " reached its clicks limit (" + link.getLimit() + ") and is blocked. "
                        + "Raise it with: edit " + shortLink + " limit <n>, "
                        + "or create a new one: shorten " + link.getUrl();
                break;
            case EXPIRED:
                txt = "Your link " + shortLink + " expired and was deleted. Create a new one: shorten " + link.getUrl();
                break;
            default:
                return;
        }
        store.add(link.getOwnerId(), txt);
    }

    public synchronized List<String> take(UUID ownerId) {
        if (ownerId == null) return List.of();
        return store.take(ownerId);
    }
}
