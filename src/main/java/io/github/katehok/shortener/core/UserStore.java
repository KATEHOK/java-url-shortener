package io.github.katehok.shortener.core;

import java.util.Optional;
import java.util.UUID;

public interface UserStore {

    Optional<UUID> load();

    void save(UUID userId);
}
