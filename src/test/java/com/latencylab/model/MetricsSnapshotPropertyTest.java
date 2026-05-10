package com.latencylab.model;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

class MetricsSnapshotPropertyTest {

    // Feature: latencylab-phase1-setup, Property 6: MetricsSnapshot construction preserves all field values
    @Property(tries = 100)
    void constructionPreservesFieldValues(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1000000) long totalRequests,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1000000) long successfulRequests,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1000000) long failedRequests,
            @ForAll long avgLatencyNanos,
            @ForAll long minLatencyNanos,
            @ForAll long maxLatencyNanos,
            @ForAll long p50LatencyNanos,
            @ForAll long p95LatencyNanos,
            @ForAll long p99LatencyNanos,
            @ForAll double requestsPerSecond,
            @ForAll long snapshotTimestamp
    ) {
        Assume.that(totalRequests - successfulRequests >= failedRequests);

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
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1000000) long totalRequests,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1000000) long successfulRequests,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 1000000) long failedRequests
    ) {
        Assume.that(totalRequests - successfulRequests < failedRequests);

        assertThrows(IllegalArgumentException.class, () -> {
            new MetricsSnapshot(totalRequests, successfulRequests, failedRequests,
                    0, 0, 0, 0, 0, 0, 0.0, 0);
        });
    }
}
