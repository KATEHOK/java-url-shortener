package io.github.katehok.shortener.infra;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.katehok.shortener.core.LinkRepository;
import io.github.katehok.shortener.core.ShortLink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Храню в TSV - табов в url не бывает, URI их не пропускает
public class FileLinkRepository implements LinkRepository {

    private static final String SEPARATOR = "\t";
    private static final int FIELDS = 7;

    private final Path path;
    private MemoryLinkRepository memory;

    public FileLinkRepository(Path path) throws IOException {
        this.path = path;
        this.memory = load();
    }

    @Override
    public void save(ShortLink link) {
        memory.save(link);
        write();
    }

    @Override
    public Optional<ShortLink> find(String code) {
        return memory.find(code);
    }

    @Override
    public List<ShortLink> findByOwner(UUID ownerId) {
        return memory.findByOwner(ownerId);
    }

    @Override
    public List<ShortLink> findAll() {
        return memory.findAll();
    }

    @Override
    public boolean exists(String code) {
        return memory.exists(code);
    }

    @Override
    public void delete(String code) {
        memory.delete(code);
        write();
    }

    private MemoryLinkRepository load() throws IOException {
        MemoryLinkRepository loaded = new MemoryLinkRepository();
        if (!Files.exists(path)) return loaded;
        List<String> lines;
        try {
            lines = Files.readAllLines(path, UTF_8);
        } catch (CharacterCodingException e) {
            throw new IOException(path + " is not a valid UTF-8 file", e);
        }
        for (int i = 0; i < lines.size(); ++i) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            ShortLink link = tryParse(line);
            if (link == null) throw new IOException("Broken line " + (i + 1) + " in " + path);
            loaded.save(link);
        }
        return loaded;
    }

    private MemoryLinkRepository tryLoad() {
        try {
            return load();
        } catch (IOException e) {
            return null;
        }
    }

    private void restore() {
        MemoryLinkRepository loaded = tryLoad();
        if (loaded != null) memory = loaded;
    }

    private ShortLink tryParse(String line) {
        String[] parts = line.split(SEPARATOR, -1);
        if (parts.length != FIELDS) return null;
        try {
            int limit = Integer.parseInt(parts[5]);
            int clicks = Integer.parseInt(parts[6]);
            if (parts[0].isEmpty() || parts[1].isEmpty() || limit < 1 || clicks < 0) return null;
            return new ShortLink(parts[0], parts[1], UUID.fromString(parts[2]),
                    Instant.parse(parts[3]), Instant.parse(parts[4]), limit, clicks);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return null;
        }
    }

    private String format(ShortLink link) {
        return String.join(SEPARATOR,
                link.getCode(),
                link.getUrl(),
                link.getOwnerId().toString(),
                link.getCreatedAt().toString(),
                link.getExpiresAt().toString(),
                Integer.toString(link.getLimit()),
                Integer.toString(link.getClicks()));
    }

    private void write() {
        StringBuilder sb = new StringBuilder();
        for (ShortLink link : memory.findAll()) {
            sb.append(format(link)).append('\n');
        }
        // Пишу во временный файл и подменяю атомарно, чтобы при падении не остаться с битым файлом
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(tmp, sb, UTF_8);
            Files.move(tmp, path, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (IOException e) {
            restore();
            throw new UncheckedIOException("Couldn't write " + path, e);
        }
    }
}
