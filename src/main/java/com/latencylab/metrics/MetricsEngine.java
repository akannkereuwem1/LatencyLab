package com.latencylab.metrics;

import com.latencylab.model.MetricsSnapshot;

public interface MetricsEngine {
    void record(long latencyNanos, boolean success);
    MetricsSnapshot snapshot();
}
