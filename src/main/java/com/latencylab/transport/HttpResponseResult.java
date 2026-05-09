package com.latencylab.transport;

public record HttpResponseResult(
        int statusCode,
        String responseBody,
        long latencyNanos
) {}
