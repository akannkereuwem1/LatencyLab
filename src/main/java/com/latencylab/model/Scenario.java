package com.latencylab.model;

import java.util.List;
import java.util.Objects;

public record Scenario(
        String testName,
        List<RequestStep> steps,
        int rampUpSeconds,
        int durationSeconds,
        int userCount
) {
    public Scenario {
        Objects.requireNonNull(testName, "testName cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps cannot be empty");
        }
        if (rampUpSeconds < 0 || rampUpSeconds > 3600) {
            throw new IllegalArgumentException("rampUpSeconds must be between 0 and 3600");
        }
        if (durationSeconds < 1 || durationSeconds > 86400) {
            throw new IllegalArgumentException("durationSeconds must be between 1 and 86400");
        }
        if (userCount < 1 || userCount > 100000) {
            throw new IllegalArgumentException("userCount must be between 1 and 100000");
        }

        steps = List.copyOf(steps);
    }
}
