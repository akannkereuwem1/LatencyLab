package com.latencylab.metrics;

import com.latencylab.model.MetricsSnapshot;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMetricsEnginePropertyTest {

    // Feature: latencylab-phase4-metrics-aggregation, Property 1: Counter consistency invariant
    @Property(tries = 100)
    void counterConsistencyInvariant(
            @ForAll @Size(min = 1, max = 50) List<@LongRange(min = 0) Long> latencies,
            @ForAll @Size(min = 1, max = 50) List<Boolean> successFlags
    ) {
        int n = Math.min(latencies.size(), successFlags.size());
        Assume.that(n > 0);

        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        long trueCount = 0;
        long falseCount = 0;
        for (int i = 0; i < n; i++) {
            boolean success = successFlags.get(i);
            engine.record(latencies.get(i), success);
            if (success) {
                trueCount++;
            } else {
                falseCount++;
            }
        }

        MetricsSnapshot snapshot = engine.snapshot();
        assertEquals(n, snapshot.totalRequests());
        assertEquals(trueCount, snapshot.successfulRequests());
        assertEquals(falseCount, snapshot.failedRequests());
        assertEquals(snapshot.totalRequests(), snapshot.successfulRequests() + snapshot.failedRequests());
    }

    // Feature: latencylab-phase4-metrics-aggregation, Property 2: Latency ordering invariant
    @Property(tries = 100)
    void latencyOrderingInvariant(
            @ForAll @NotEmpty @Size(min = 1, max = 50) List<@LongRange(min = 0, max = Long.MAX_VALUE / 2) Long> latencies
    ) {
        Assume.that(canSumWithoutOverflow(latencies));

        DefaultMetricsEngine engine = new DefaultMetricsEngine();
        for (Long latency : latencies) {
            engine.record(latency, true);
        }

        MetricsSnapshot snapshot = engine.snapshot();

        assertTrue(snapshot.minLatencyNanos() <= snapshot.avgLatencyNanos());
        assertTrue(snapshot.avgLatencyNanos() <= snapshot.maxLatencyNanos());
        assertTrue(snapshot.p50LatencyNanos() <= snapshot.p95LatencyNanos());
        assertTrue(snapshot.p95LatencyNanos() <= snapshot.p99LatencyNanos());
    }

    // Feature: latencylab-phase4-metrics-aggregation, Property 3: Percentile index formula correctness
    @Property(tries = 100)
    void percentileIndexFormulaCorrectness(@ForAll @Size(min = 1, max = 200) long[] values) {
        Arrays.sort(values);
        int n = values.length;

        int p50Index = (int) Math.floor(0.50 * n);
        int p95Index = (int) Math.floor(0.95 * n);
        int p99Index = (int) Math.floor(0.99 * n);

        assertTrue(p50Index >= 0 && p50Index < n);
        assertTrue(p95Index >= 0 && p95Index < n);
        assertTrue(p99Index >= 0 && p99Index < n);

        long min = values[0];
        long max = values[n - 1];

        assertTrue(values[p50Index] >= min && values[p50Index] <= max);
        assertTrue(values[p95Index] >= min && values[p95Index] <= max);
        assertTrue(values[p99Index] >= min && values[p99Index] <= max);
    }

    // Feature: latencylab-phase4-metrics-aggregation, Property 4: Thread-safe concurrent recording
    @Property(tries = 100)
    void threadSafeConcurrentRecordingNoLostUpdates(
            @ForAll @IntRange(min = 2, max = 50) int threadCount,
            @ForAll @IntRange(min = 1, max = 100) int recordsPerThread
    ) throws InterruptedException {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread thread = Thread.ofVirtual().start(() -> {
                for (int j = 0; j < recordsPerThread; j++) {
                    engine.record(1000L, true);
                }
            });
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long expected = (long) threadCount * recordsPerThread;
        MetricsSnapshot snapshot = engine.snapshot();

        assertEquals(expected, snapshot.totalRequests());
        assertEquals(expected, snapshot.successfulRequests());
    }

    // Feature: latencylab-phase4-metrics-aggregation, Property 5: RPS is non-negative and finite
    @Property(tries = 100)
    void rpsIsNonNegativeAndFinite(@ForAll @IntRange(min = 0, max = 200) int recordCount) {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        for (int i = 0; i < recordCount; i++) {
            engine.record(1000L, true);
        }

        double rps = engine.snapshot().requestsPerSecond();

        assertTrue(rps >= 0.0);
        assertFalse(Double.isNaN(rps));
        assertFalse(Double.isInfinite(rps));
    }

    // Feature: latencylab-phase4-metrics-aggregation, Property 6: Snapshot idempotency
    @Property(tries = 100)
    void snapshotIdempotency(
            @ForAll @NotEmpty @Size(min = 1, max = 50) List<@LongRange(min = 0) Long> latencies
    ) {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        for (Long latency : latencies) {
            engine.record(latency, true);
        }

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

    // Feature: latencylab-phase4-metrics-aggregation, Property 7: Min and max correctness
    @Property(tries = 100)
    void minAndMaxCorrectnessExactValues(
            @ForAll @NotEmpty @Size(min = 1, max = 50) List<@LongRange(min = 0) Long> latencies
    ) {
        DefaultMetricsEngine engine = new DefaultMetricsEngine();

        for (Long latency : latencies) {
            engine.record(latency, true);
        }

        MetricsSnapshot snapshot = engine.snapshot();

        assertEquals(Collections.min(latencies).longValue(), snapshot.minLatencyNanos());
        assertEquals(Collections.max(latencies).longValue(), snapshot.maxLatencyNanos());
    }

    private boolean canSumWithoutOverflow(List<Long> latencies) {
        long sum = 0L;
        for (Long latency : latencies) {
            try {
                sum = Math.addExact(sum, latency);
            } catch (ArithmeticException ex) {
                return false;
            }
        }
        return true;
    }
}
