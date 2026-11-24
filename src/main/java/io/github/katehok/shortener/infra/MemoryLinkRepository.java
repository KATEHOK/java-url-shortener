package io.github.katehok.shortener.infra;

import io.github.katehok.shortener.core.LinkRepository;
import io.github.katehok.shortener.core.ShortLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class MemoryLinkRepository implements LinkRepository {

    private final Map<String, ShortLink> links = new LinkedHashMap<>();

    @Override
    public void save(ShortLink link) {
        links.put(link.getCode(), link);
    }

    @Override
    public Optional<ShortLink> find(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public List<ShortLink> findByOwner(UUID ownerId) {
        return links.values().stream()
                .filter(link -> link.isOwnedBy(ownerId))
                .toList();
    }

    @Override
    public List<ShortLink> findAll() {
        return new ArrayList<>(links.values());
    }

    @Override
    public boolean exists(String code) {
        return links.containsKey(code);
    }

    @Override
    public void delete(String code) {
        links.remove(code);
    }
}
