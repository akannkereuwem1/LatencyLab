package com.latencylab.metrics;

import com.latencylab.model.MetricsSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free, thread-safe implementation of {@link MetricsEngine}.
 *
 * <p>Uses atomic counters for aggregate values, CAS loops for running min/max,
 * and a copy-on-write buffer for latency samples used during snapshot percentile
 * calculation.
 */
public class DefaultMetricsEngine implements MetricsEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultMetricsEngine.class);

    private final AtomicLong totalCounter = new AtomicLong(0);
    private final AtomicLong successCounter = new AtomicLong(0);
    private final AtomicLong failureCounter = new AtomicLong(0);
    private final AtomicLong runningSum = new AtomicLong(0);
    private final AtomicLong runningMin = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong runningMax = new AtomicLong(0);

    private final CopyOnWriteArrayList<Long> latencyBuffer = new CopyOnWriteArrayList<>();
    private final long startTimestamp;

    public DefaultMetricsEngine() {
        this.startTimestamp = System.nanoTime();
        log.debug("DefaultMetricsEngine initialized, startTimestamp={}", startTimestamp);
    }

    @Override
    public void record(long latencyNanos, boolean success) {
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be >= 0, received=" + latencyNanos);
        }

        totalCounter.incrementAndGet();
        if (success) {
            successCounter.incrementAndGet();
        } else {
            failureCounter.incrementAndGet();
        }

        runningSum.addAndGet(latencyNanos);

        while (latencyNanos < runningMin.get()) {
            long current = runningMin.get();
            if (latencyNanos >= current) {
                break;
            }
            if (runningMin.compareAndSet(current, latencyNanos)) {
                break;
            }
        }

        while (latencyNanos > runningMax.get()) {
            long current = runningMax.get();
            if (latencyNanos <= current) {
                break;
            }
            if (runningMax.compareAndSet(current, latencyNanos)) {
                break;
            }
        }

        latencyBuffer.add(latencyNanos);
        log.debug("record: latencyNanos={}, success={}", latencyNanos, success);
    }

    @Override
    public MetricsSnapshot snapshot() {
        long snapshotTimestamp = System.nanoTime();

        long total = totalCounter.get();
        long success = successCounter.get();
        long failure = failureCounter.get();
        long sum = runningSum.get();

        if (total == 0) {
            return new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, snapshotTimestamp);
        }

        long avg = sum / total;
        long min = runningMin.get();
        long max = runningMax.get();

        long[] arr = new long[latencyBuffer.size()];
        for (int i = 0; i < latencyBuffer.size(); i++) {
            arr[i] = latencyBuffer.get(i);
        }
        Arrays.sort(arr);
        int n = arr.length;

        long p50 = arr[(int) Math.floor(0.50 * n)];
        long p95 = arr[(int) Math.floor(0.95 * n)];
        long p99 = arr[(int) Math.floor(0.99 * n)];

        double elapsed = (System.nanoTime() - startTimestamp) / 1_000_000_000.0;
        double rps = (elapsed > 0.0) ? (double) total / elapsed : 0.0;

        log.debug("snapshot: totalRequests={}, requestsPerSecond={}", total, rps);

        return new MetricsSnapshot(total, success, failure, avg, min, max, p50, p95, p99, rps, snapshotTimestamp);
    }
}
