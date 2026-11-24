package io.github.katehok.shortener.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkRepository {

    void save(ShortLink link);

    Optional<ShortLink> find(String code);

    List<ShortLink> findByOwner(UUID ownerId);

    List<ShortLink> findAll();

    boolean exists(String code);

    void delete(String code);
}
