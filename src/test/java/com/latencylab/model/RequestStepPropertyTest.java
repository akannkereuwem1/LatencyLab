package com.latencylab.model;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestStepPropertyTest {

    @Provide
    Arbitrary<HttpMethod> httpMethods() {
        return Arbitraries.of(HttpMethod.class);
    }

    // Feature: latencylab-phase1-setup, Property 1: RequestStep construction preserves all field values
    @Property(tries = 100)
    void constructionPreservesFieldValues(
            @ForAll String name,
            @ForAll("httpMethods") HttpMethod method,
            @ForAll String endpoint,
            @ForAll String body,
            @ForAll Map<String, String> headers,
            @ForAll @IntRange(min = 1, max = 300000) int timeoutMillis
    ) {
        RequestStep step = new RequestStep(name, method, endpoint, body, headers, timeoutMillis);

        assertEquals(name, step.name());
        assertEquals(method, step.method());
        assertEquals(endpoint, step.endpoint());
        assertEquals(body, step.body());
        assertEquals(headers, step.headers());
        assertEquals(timeoutMillis, step.timeoutMillis());
    }

    // Feature: latencylab-phase1-setup, Property 2: RequestStep rejects out-of-range timeoutMillis
    @Property(tries = 100)
    void rejectsOutOfRangeTimeoutMillis(
            @ForAll String name,
            @ForAll("httpMethods") HttpMethod method,
            @ForAll String endpoint,
            @ForAll String body,
            @ForAll Map<String, String> headers,
            @ForAll int timeoutMillis
    ) {
        Assume.that(timeoutMillis < 1 || timeoutMillis > 300000);

        assertThrows(IllegalArgumentException.class, () -> {
            new RequestStep(name, method, endpoint, body, headers, timeoutMillis);
        });
    }

    // Feature: latencylab-phase1-setup, Property 9: Record immutability — headers map is defensively copied
    @Property(tries = 100)
    void headersMapIsDefensivelyCopied(
            @ForAll String name,
            @ForAll("httpMethods") HttpMethod method,
            @ForAll String endpoint,
            @ForAll String body,
            @ForAll @IntRange(min = 1, max = 300000) int timeoutMillis
    ) {
        Map<String, String> originalHeaders = new HashMap<>();
        originalHeaders.put("Key1", "Value1");

        RequestStep step = new RequestStep(name, method, endpoint, body, originalHeaders, timeoutMillis);

        // Modify the original map
        originalHeaders.put("Key2", "Value2");

        // The step's headers should not contain the new key
        assertFalse(step.headers().containsKey("Key2"));
        assertEquals(1, step.headers().size());
        
        // Ensure the returned map is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> {
            step.headers().put("Key3", "Value3");
        });
    }
}
