package io.github.katehok.shortener.core;

import java.util.List;
import java.util.UUID;

public interface NoticeStore {

    void add(UUID ownerId, String txt);

    List<String> take(UUID ownerId);
}
