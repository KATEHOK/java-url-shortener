package io.github.katehok.shortener;

import io.github.katehok.shortener.core.UserStore;
import java.util.Optional;
import java.util.UUID;

public class MemoryUserStore implements UserStore {

    private UUID userId;

    @Override
    public Optional<UUID> load() {
        return Optional.ofNullable(userId);
    }

    @Override
    public void save(UUID userId) {
        this.userId = userId;
    }
}
