package com.latencylab.model;

public record MetricsSnapshot(
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long avgLatencyNanos,
        long minLatencyNanos,
        long maxLatencyNanos,
        long p50LatencyNanos,
        long p95LatencyNanos,
        long p99LatencyNanos,
        double requestsPerSecond,
        long snapshotTimestamp
) {
    public MetricsSnapshot {
        if (successfulRequests < 0 || failedRequests < 0 || totalRequests < 0) {
            throw new IllegalArgumentException("Request counts cannot be negative");
        }
        if (successfulRequests + failedRequests > totalRequests) {
            throw new IllegalArgumentException("successfulRequests + failedRequests cannot exceed totalRequests");
        }
    }
}
