package com.agenticsdlc.orchestrator.app.api;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * The executor that writes server-sent events to the clients: one virtual thread per send task, at most
 * {@code permits} tasks at a time (further tasks are rejected). A blocked write to a slow or dead client therefore
 * only parks a virtual thread and never starves the writes of other streams, while the number of concurrent writes
 * stays bounded (the hub runs at most one send task per stream).
 */
final class BoundedSendExecutor implements Executor, AutoCloseable {

    private final Semaphore permits;
    private final ExecutorService threads = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sdlc-sse-", 0).factory());

    /**
     * Creates the executor.
     *
     * @param maxConcurrent maximum number of tasks running at the same time (at least 1)
     */
    BoundedSendExecutor(int maxConcurrent) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    @Override
    public void execute(Runnable task) {
        if (!permits.tryAcquire()) {
            throw new RejectedExecutionException("too many concurrent event stream writes");
        }
        try {
            threads.execute(() -> {
                try {
                    task.run();
                } finally {
                    permits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            permits.release();
            throw e;
        }
    }

    /**
     * Stops accepting tasks and interrupts running writes.
     */
    @Override
    public void close() {
        threads.shutdownNow();
    }
}
