package com.example.local_notebooklm.inference.distributed.model;

public record NodeStatus(
        String id,
        String baseUrl,
        boolean healthy,
        int activeRequests,
        int availableSlots,
        int failureCount
) {
}
