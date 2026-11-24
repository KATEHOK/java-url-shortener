package io.github.katehok.shortener.core;

import java.time.Duration;

public record Settings(Duration ttl, int defaultLimit, int maxLimit, int maxUrlLength, int codeAttempts) {
}
