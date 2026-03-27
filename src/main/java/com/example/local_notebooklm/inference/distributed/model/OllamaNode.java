package com.example.local_notebooklm.inference.distributed.model;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class OllamaNode {

    private final String id;
    private final String baseUrl;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final Semaphore semaphore;

    public OllamaNode(String id, String baseUrl, int maxConcurrent) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.semaphore = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    public String getId() {
        return id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
        activeRequests.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void incrementActive() {
        activeRequests.incrementAndGet();
    }

    public int getAvailableSlots() {
        return semaphore.availablePermits();
    }

    public void markHealthy() {
        healthy.set(true);
        failureCount.set(0);
    }

    public void recordFailure(int max) {
        int failures = failureCount.incrementAndGet();
        if (failures >= Math.max(1, max)) {
            healthy.set(false);
        }
    }

    public NodeStatus toStatus() {
        return new NodeStatus(
                id,
                baseUrl,
                healthy.get(),
                activeRequests.get(),
                semaphore.availablePermits(),
                failureCount.get()
        );
    }
}
