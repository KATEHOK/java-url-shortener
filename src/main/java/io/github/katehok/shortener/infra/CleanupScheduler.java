package io.github.katehok.shortener.infra;

import io.github.katehok.shortener.core.LinkService;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CleanupScheduler implements AutoCloseable {

    private static final String THREAD_NAME = "cleanup";

    private final LinkService service;
    private final Duration period;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(CleanupScheduler::newThread);

    public CleanupScheduler(LinkService service, Duration period) {
        if (period.toMillis() < 1) throw new IllegalArgumentException("Cleanup period must be at least 1 ms");
        this.service = service;
        this.period = period;
    }

    public void start() {
        executor.scheduleAtFixedRate(this::runOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // Ловлю все, иначе ScheduledExecutorService молча отменит следующие запуски
    public void runOnce() {
        try {
            service.purgeExpired();
        } catch (RuntimeException e) {
            System.err.println("Cleanup failed: " + e.getMessage());
        }
    }

    private static Thread newThread(Runnable task) {
        Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    }
}
