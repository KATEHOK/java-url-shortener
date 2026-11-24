package io.github.katehok.shortener.infra;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.katehok.shortener.core.UserStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public class FileUserStore implements UserStore {

    private final Path path;

    public FileUserStore(Path path) {
        this.path = path;
    }

    @Override
    public Optional<UUID> load() {
        if (!Files.exists(path)) return Optional.empty();
        String txt;
        try {
            txt = Files.readString(path, UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + path, e);
        }
        return parse(txt);
    }

    @Override
    public void save(UUID userId) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, userId + "\n", UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't write " + path, e);
        }
    }

    private Optional<UUID> parse(String txt) {
        UUID userId;
        try {
            userId = UUID.fromString(txt);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!userId.toString().equalsIgnoreCase(txt)) return Optional.empty();
        return Optional.of(userId);
    }
}
