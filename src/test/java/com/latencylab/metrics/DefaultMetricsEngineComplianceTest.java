package com.latencylab.metrics;

import com.latencylab.model.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface and behavior compliance tests for {@link DefaultMetricsEngine}.
 */
class DefaultMetricsEngineComplianceTest {

    @Test
    void testImplementsMetricsEngine() {
        assertTrue(MetricsEngine.class.isAssignableFrom(DefaultMetricsEngine.class),
                "DefaultMetricsEngine should implement MetricsEngine");
    }

    @Test
    void testSnapshotDoesNotResetState() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();
        engine.record(100L, true);
        engine.record(200L, true);
        engine.record(400L, false);

        MetricsSnapshot first = engine.snapshot();
        MetricsSnapshot second = engine.snapshot();

        assertEquals(first.totalRequests(), second.totalRequests());
        assertEquals(first.avgLatencyNanos(), second.avgLatencyNanos());
        assertEquals(first.minLatencyNanos(), second.minLatencyNanos());
        assertEquals(first.maxLatencyNanos(), second.maxLatencyNanos());
        assertEquals(first.p50LatencyNanos(), second.p50LatencyNanos());
        assertEquals(first.p95LatencyNanos(), second.p95LatencyNanos());
        assertEquals(first.p99LatencyNanos(), second.p99LatencyNanos());
    }

    @Test
    void testRpsIsNonNegative() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        MetricsSnapshot zeroRecords = engine.snapshot();
        assertTrue(zeroRecords.requestsPerSecond() >= 0.0);

        engine.record(100L, true);
        MetricsSnapshot oneRecord = engine.snapshot();
        assertTrue(oneRecord.requestsPerSecond() >= 0.0);

        engine.record(200L, true);
        engine.record(300L, false);
        engine.record(400L, true);
        MetricsSnapshot multipleRecords = engine.snapshot();
        assertTrue(multipleRecords.requestsPerSecond() >= 0.0);
    }

    @Test
    void testAvgIsIntegerDivision() {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        engine.record(100L, true);
        engine.record(200L, true);
        engine.record(300L, true);

        MetricsSnapshot snapshot = engine.snapshot();
        assertEquals(200L, snapshot.avgLatencyNanos());
    }
}
