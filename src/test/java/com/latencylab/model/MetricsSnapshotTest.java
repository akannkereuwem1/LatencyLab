package com.latencylab.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsSnapshotTest {

    @Test
    void validConstructionAndAccessorRoundTrip() {
        MetricsSnapshot snapshot = new MetricsSnapshot(100, 90, 5, 1000, 100, 2000, 500, 1500, 1900, 50.0, 123456789L);
        
        assertEquals(100, snapshot.totalRequests());
        assertEquals(90, snapshot.successfulRequests());
        assertEquals(5, snapshot.failedRequests());
        assertEquals(1000, snapshot.avgLatencyNanos());
        assertEquals(100, snapshot.minLatencyNanos());
        assertEquals(2000, snapshot.maxLatencyNanos());
        assertEquals(500, snapshot.p50LatencyNanos());
        assertEquals(1500, snapshot.p95LatencyNanos());
        assertEquals(1900, snapshot.p99LatencyNanos());
        assertEquals(50.0, snapshot.requestsPerSecond());
        assertEquals(123456789L, snapshot.snapshotTimestamp());
    }

    @Test
    void sumExceedsTotalThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> 
            new MetricsSnapshot(100, 90, 11, 0, 0, 0, 0, 0, 0, 0.0, 0));
    }

    @Test
    void sumEqualsTotalIsValid() {
        assertDoesNotThrow(() -> 
            new MetricsSnapshot(100, 90, 10, 0, 0, 0, 0, 0, 0, 0.0, 0));
    }
}
