package com.latencylab.model;

import java.util.Map;
import java.util.Objects;

public record RequestStep(
        String name,
        HttpMethod method,
        String endpoint,
        String body,
        Map<String, String> headers,
        int timeoutMillis) {
    public RequestStep {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(method, "method cannot be null");
        Objects.requireNonNull(endpoint, "endpoint cannot be null");
        Objects.requireNonNull(headers, "headers cannot be null");

        if (timeoutMillis < 0 || timeoutMillis > 300000) {
            throw new IllegalArgumentException("timeoutMillis must be between 0 and 300000");
        }

        headers = Map.copyOf(headers);
    }
}
