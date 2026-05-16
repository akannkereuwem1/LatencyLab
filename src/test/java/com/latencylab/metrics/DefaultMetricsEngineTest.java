package com.latencylab.metrics;

import com.latencylab.model.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMetricsEngineTest {

    @Test
    void testZeroStateSnapshot() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();
        MetricsSnapshot snapshot = engine.snapshot();

        assertEquals(0, snapshot.totalRequests());
        assertEquals(0, snapshot.successfulRequests());
        assertEquals(0, snapshot.failedRequests());
        assertEquals(0, snapshot.avgLatencyNanos());
        assertEquals(0, snapshot.minLatencyNanos());
        assertEquals(0, snapshot.maxLatencyNanos());
        assertEquals(0, snapshot.p50LatencyNanos());
        assertEquals(0, snapshot.p95LatencyNanos());
        assertEquals(0, snapshot.p99LatencyNanos());
        assertEquals(0.0, snapshot.requestsPerSecond());
    }

    @Test
    void testSingleRecord() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        engine.record(1000L, true);
        MetricsSnapshot snapshot = engine.snapshot();

        assertEquals(1, snapshot.totalRequests());
        assertEquals(1, snapshot.successfulRequests());
        assertEquals(0, snapshot.failedRequests());
        assertEquals(1000L, snapshot.avgLatencyNanos());
        assertEquals(1000L, snapshot.minLatencyNanos());
        assertEquals(1000L, snapshot.maxLatencyNanos());
        assertEquals(1000L, snapshot.p50LatencyNanos());
        assertEquals(1000L, snapshot.p95LatencyNanos());
        assertEquals(1000L, snapshot.p99LatencyNanos());
    }

    @Test
    void testRecordIncrementsTotalRequests() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        for (int i = 1; i <= 5; i++) {
            engine.record(i * 100L, true);
            assertEquals(i, engine.snapshot().totalRequests());
        }
    }

    @Test
    void testSuccessFailureCounting() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        engine.record(100L, true);
        engine.record(200L, false);
        engine.record(300L, false);

        MetricsSnapshot snapshot = engine.snapshot();

        assertEquals(3, snapshot.totalRequests());
        assertEquals(1, snapshot.successfulRequests());
        assertEquals(2, snapshot.failedRequests());
    }

    @Test
    void testCounterSumInvariant() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        engine.record(100L, true);
        engine.record(200L, false);
        engine.record(300L, true);
        engine.record(400L, false);
        engine.record(500L, true);

        MetricsSnapshot snapshot = engine.snapshot();
        assertEquals(snapshot.totalRequests(), snapshot.successfulRequests() + snapshot.failedRequests());
    }

    @Test
    void testNegativeLatencyThrows() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        assertThrows(IllegalArgumentException.class, () -> engine.record(-1L, true));
        assertEquals(0, engine.snapshot().totalRequests());
    }

    @Test
    void testZeroLatencyAccepted() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        assertDoesNotThrow(() -> engine.record(0L, true));
        MetricsSnapshot snapshot = engine.snapshot();
        assertEquals(1, snapshot.totalRequests());
        assertEquals(0L, snapshot.minLatencyNanos());
    }

    @Test
    void testLongMaxValueAccepted() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        assertDoesNotThrow(() -> engine.record(Long.MAX_VALUE, true));
        MetricsSnapshot snapshot = engine.snapshot();
        assertEquals(1, snapshot.totalRequests());
        assertEquals(Long.MAX_VALUE, snapshot.maxLatencyNanos());
    }

    @Test
    void testSnapshotTimestampIsRecent() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        long before = System.nanoTime();
        MetricsSnapshot snapshot = engine.snapshot();
        long after = System.nanoTime();

        assertTrue(snapshot.snapshotTimestamp() >= before);
        assertTrue(snapshot.snapshotTimestamp() <= after);
    }

    @Test
    void testThreadSafety50VirtualThreads() throws InterruptedException {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            threads.add(Thread.ofVirtual().start(() -> engine.record(1000L, true)));
        }

        for (Thread thread : threads) {
            thread.join();
        }

        MetricsSnapshot snapshot = engine.snapshot();
        assertEquals(50, snapshot.totalRequests());
        assertEquals(50, snapshot.successfulRequests());
    }

    @Test
    void testSnapshotDoesNotResetState() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        engine.record(100L, true);
        engine.record(200L, false);
        engine.record(300L, true);

        MetricsSnapshot first = engine.snapshot();
        MetricsSnapshot second = engine.snapshot();

        assertEquals(first.totalRequests(), second.totalRequests());
        assertEquals(first.successfulRequests(), second.successfulRequests());
        assertEquals(first.failedRequests(), second.failedRequests());
        assertEquals(first.avgLatencyNanos(), second.avgLatencyNanos());
        assertEquals(first.minLatencyNanos(), second.minLatencyNanos());
        assertEquals(first.maxLatencyNanos(), second.maxLatencyNanos());
        assertEquals(first.p50LatencyNanos(), second.p50LatencyNanos());
        assertEquals(first.p95LatencyNanos(), second.p95LatencyNanos());
        assertEquals(first.p99LatencyNanos(), second.p99LatencyNanos());
    }
}
