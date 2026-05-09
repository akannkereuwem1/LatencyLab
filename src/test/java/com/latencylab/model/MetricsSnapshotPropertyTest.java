package com.latencylab.model;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

class MetricsSnapshotPropertyTest {

    // Feature: latencylab-phase1-setup, Property 6: MetricsSnapshot construction preserves all field values
    @Property(tries = 100)
    void constructionPreservesFieldValues(
            @ForAll long totalRequests,
            @ForAll long successfulRequests,
            @ForAll long failedRequests,
            @ForAll long avgLatencyNanos,
            @ForAll long minLatencyNanos,
            @ForAll long maxLatencyNanos,
            @ForAll long p50LatencyNanos,
            @ForAll long p95LatencyNanos,
            @ForAll long p99LatencyNanos,
            @ForAll double requestsPerSecond,
            @ForAll long snapshotTimestamp
    ) {
        Assume.that(totalRequests >= 0 && successfulRequests >= 0 && failedRequests >= 0);
        // Avoid overflow during generation checking
        Assume.that(successfulRequests <= totalRequests);
        Assume.that(failedRequests <= totalRequests - successfulRequests);

        MetricsSnapshot snapshot = new MetricsSnapshot(
                totalRequests, successfulRequests, failedRequests,
                avgLatencyNanos, minLatencyNanos, maxLatencyNanos,
                p50LatencyNanos, p95LatencyNanos, p99LatencyNanos,
                requestsPerSecond, snapshotTimestamp
        );

        assertEquals(totalRequests, snapshot.totalRequests());
        assertEquals(successfulRequests, snapshot.successfulRequests());
        assertEquals(failedRequests, snapshot.failedRequests());
        assertEquals(avgLatencyNanos, snapshot.avgLatencyNanos());
        assertEquals(minLatencyNanos, snapshot.minLatencyNanos());
        assertEquals(maxLatencyNanos, snapshot.maxLatencyNanos());
        assertEquals(p50LatencyNanos, snapshot.p50LatencyNanos());
        assertEquals(p95LatencyNanos, snapshot.p95LatencyNanos());
        assertEquals(p99LatencyNanos, snapshot.p99LatencyNanos());
        assertEquals(requestsPerSecond, snapshot.requestsPerSecond());
        assertEquals(snapshotTimestamp, snapshot.snapshotTimestamp());
    }

    // Feature: latencylab-phase1-setup, Property 5: MetricsSnapshot request count invariant
    @Property(tries = 100)
    void requestCountInvariant(
            @ForAll long totalRequests,
            @ForAll long successfulRequests,
            @ForAll long failedRequests
    ) {
        Assume.that(totalRequests >= 0 && successfulRequests >= 0 && failedRequests >= 0);
        // We want to test when the invariant is violated (sum > totalRequests)
        boolean invalid = false;
        if (successfulRequests > totalRequests) {
            invalid = true;
        } else if (failedRequests > totalRequests - successfulRequests) {
            invalid = true;
        }
        Assume.that(invalid);

        assertThrows(IllegalArgumentException.class, () -> {
            new MetricsSnapshot(totalRequests, successfulRequests, failedRequests,
                    0, 0, 0, 0, 0, 0, 0.0, 0);
        });
    }
}
