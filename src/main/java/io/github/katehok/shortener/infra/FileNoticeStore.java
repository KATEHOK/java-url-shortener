package io.github.katehok.shortener.infra;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.katehok.shortener.core.NoticeStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FileNoticeStore implements NoticeStore {

    private static final String SEPARATOR = "\t";

    private final Path path;
    private MemoryNoticeStore memory;

    public FileNoticeStore(Path path) throws IOException {
        this.path = path;
        this.memory = load();
    }

    @Override
    public void add(UUID ownerId, String txt) {
        memory.add(ownerId, txt.replaceAll("[\\t\\r\\n]", " "));
        write();
    }

    @Override
    public List<String> take(UUID ownerId) {
        List<String> taken = memory.take(ownerId);
        // take зовется после каждой команды, поэтому переписываю файл, только если что-то забрали
        if (!taken.isEmpty()) write();
        return taken;
    }

    private MemoryNoticeStore load() throws IOException {
        MemoryNoticeStore loaded = new MemoryNoticeStore();
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
            if (!tryParse(line, loaded)) throw new IOException("Broken line " + (i + 1) + " in " + path);
        }
        return loaded;
    }

    private MemoryNoticeStore tryLoad() {
        try {
            return load();
        } catch (IOException e) {
            return null;
        }
    }

    private void restore() {
        MemoryNoticeStore loaded = tryLoad();
        if (loaded != null) memory = loaded;
    }

    private boolean tryParse(String line, MemoryNoticeStore loaded) {
        String[] parts = line.split(SEPARATOR, -1);
        if (parts.length != 2 || parts[1].isBlank()) return false;
        try {
            loaded.add(UUID.fromString(parts[0]), parts[1]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    private void write() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, List<String>> entry : memory.findAll().entrySet()) {
            for (String txt : entry.getValue()) {
                sb.append(entry.getKey()).append(SEPARATOR).append(txt).append('\n');
            }
        }
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
