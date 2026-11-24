package io.github.katehok.shortener.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUserStoreTest {

    @TempDir
    Path dir;

    @Test
    void emptyWhenFileMissing() {
        assertTrue(new FileUserStore(dir.resolve("user.id")).load().isEmpty());
    }

    @Test
    void savesAndLoadsUser() {
        Path path = dir.resolve("data/user.id");
        UUID userId = UUID.randomUUID();
        new FileUserStore(path).save(userId);
        assertEquals(Optional.of(userId), new FileUserStore(path).load());
    }

    @Test
    void emptyOnGarbage() throws IOException {
        Path path = dir.resolve("user.id");
        Files.writeString(path, "not a uuid\n");
        assertTrue(new FileUserStore(path).load().isEmpty());
    }
}
