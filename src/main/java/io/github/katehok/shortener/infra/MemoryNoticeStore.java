package io.github.katehok.shortener.infra;

import io.github.katehok.shortener.core.NoticeStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MemoryNoticeStore implements NoticeStore {

    private final Map<UUID, List<String>> notices = new LinkedHashMap<>();

    @Override
    public void add(UUID ownerId, String txt) {
        notices.computeIfAbsent(ownerId, id -> new ArrayList<>()).add(txt);
    }

    @Override
    public List<String> take(UUID ownerId) {
        List<String> taken = notices.remove(ownerId);
        if (taken == null) return List.of();
        return taken;
    }

    public Map<UUID, List<String>> findAll() {
        Map<UUID, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<String>> entry : notices.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return copy;
    }
}
