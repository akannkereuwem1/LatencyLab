package com.latencylab.model;

import java.util.Objects;

public record VirtualUser(
        String userId,
        VirtualUserState state,
        Scenario activeScenario,
        MetricsSnapshot metricsSnapshot
) {
    public VirtualUser {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
    }
}
