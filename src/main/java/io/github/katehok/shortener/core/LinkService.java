package io.github.katehok.shortener.core;

import io.github.katehok.shortener.core.Notifier.Event;
import io.github.katehok.shortener.core.ShortenerException.Reason;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class LinkService {

    private static final int MAX_PORT = 65535;

    private final LinkRepository repository;
    private final CodeGenerator generator;
    private final Notifier notifier;
    private final Clock clock;
    private final Settings settings;

    public LinkService(LinkRepository repository, CodeGenerator generator, Notifier notifier, Clock clock,
            Settings settings) {
        this.repository = repository;
        this.generator = generator;
        this.notifier = notifier;
        this.clock = clock;
        this.settings = settings;
    }

    public int getMaxLimit() {
        return settings.maxLimit();
    }

    public synchronized ShortLink shorten(UUID ownerId, String url, int limit) {
        String src = checkUrl(url);
        checkLimit(limit);
        Instant now = clock.instant();
        ShortLink link = new ShortLink(nextCode(), src, ownerId, now, now.plus(settings.ttl()), limit, 0);
        repository.save(link);
        return link;
    }

    public ShortLink shorten(UUID ownerId, String url) {
        return shorten(ownerId, url, settings.defaultLimit());
    }

    public synchronized String open(String code) {
        ShortLink link = findAlive(code);
        if (link.isExhausted()) {
            throw new ShortenerException(Reason.EXHAUSTED, "Link reached its clicks limit (" + link.getLimit() + ")");
        }
        link.click();
        repository.save(link);
        // Уведомляю сразу на переходе, который исчерпал лимит, а не на следующей неудачной попытке
        if (link.isExhausted()) notifier.send(link, Event.EXHAUSTED);
        return link.getUrl();
    }

    public synchronized List<ShortLink> list(UUID ownerId) {
        purgeExpired();
        return repository.findByOwner(ownerId);
    }

    public synchronized ShortLink changeLimit(UUID userId, String code, int limit) {
        ShortLink link = findOwned(userId, code);
        checkLimit(limit);
        // Лимит должен быть больше сделанных переходов, иначе ссылка сразу заблокируется
        if (limit <= link.getClicks()) {
            throw new ShortenerException(Reason.INVALID_LIMIT,
                    "Limit must be greater than clicks made so far (" + link.getClicks() + ")");
        }
        link.setLimit(limit);
        repository.save(link);
        return link;
    }

    public synchronized ShortLink changeUrl(UUID userId, String code, String url) {
        ShortLink link = findOwned(userId, code);
        link.setUrl(checkUrl(url));
        repository.save(link);
        return link;
    }

    public synchronized void delete(UUID userId, String code) {
        findOwned(userId, code);
        repository.delete(code);
    }

    public synchronized int purgeExpired() {
        Instant now = clock.instant();
        int count = 0;
        for (ShortLink link : repository.findAll()) {
            if (!link.isExpired(now)) continue;
            expire(link);
            ++count;
        }
        return count;
    }

    private void expire(ShortLink link) {
        repository.delete(link.getCode());
        notifier.send(link, Event.EXPIRED);
    }

    // TTL проверяю и тут - между запусками планировщика протухшая ссылка не должна открыться
    private ShortLink findAlive(String code) {
        ShortLink link = repository.find(code)
                .orElseThrow(() -> new ShortenerException(Reason.NOT_FOUND, "Link not found"));
        if (link.isExpired(clock.instant())) {
            expire(link);
            throw new ShortenerException(Reason.EXPIRED, "Link expired and was deleted");
        }
        return link;
    }

    private ShortLink findOwned(UUID userId, String code) {
        ShortLink link = findAlive(code);
        if (userId == null || !link.isOwnedBy(userId)) {
            throw new ShortenerException(Reason.NOT_OWNER, "Only the owner can change or delete this link");
        }
        return link;
    }

    private String nextCode() {
        for (int i = 0; i < settings.codeAttempts(); ++i) {
            String code = generator.next();
            if (!repository.exists(code)) return code;
        }
        throw new ShortenerException(Reason.NO_FREE_CODE, "Couldn't find a free code");
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > settings.maxLimit()) {
            throw new ShortenerException(Reason.INVALID_LIMIT,
                    "Limit must be a whole number from 1 to " + settings.maxLimit());
        }
    }

    private String checkUrl(String src) {
        if (!isValidUrl(src)) throw new ShortenerException(Reason.INVALID_URL, "Invalid URL: " + src);
        return src.strip();
    }

    private boolean isValidUrl(String src) {
        if (src == null || src.isBlank() || src.length() > settings.maxUrlLength()) return false;
        URI uri;
        try {
            uri = new URI(src.strip());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return false;
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > MAX_PORT)) return false;
        return uri.getHost() != null;
    }
}
