package com.latencylab.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestStepTest {

    @Test
    void validConstructionAndAccessorRoundTrip() {
        Map<String, String> headers = Map.of("Auth", "Bearer token");
        RequestStep step = new RequestStep("testName", HttpMethod.POST, "/api/test", "body", headers, 5000);
        
        assertEquals("testName", step.name());
        assertEquals(HttpMethod.POST, step.method());
        assertEquals("/api/test", step.endpoint());
        assertEquals("body", step.body());
        assertEquals(headers, step.headers());
        assertEquals(5000, step.timeoutMillis());
    }

    @Test
    void nullNameThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            new RequestStep(null, HttpMethod.GET, "/api", null, Map.of(), 1000));
    }

    @Test
    void nullMethodThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            new RequestStep("name", null, "/api", null, Map.of(), 1000));
    }

    @Test
    void nullEndpointThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            new RequestStep("name", HttpMethod.GET, null, null, Map.of(), 1000));
    }

    @Test
    void nullHeadersThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            new RequestStep("name", HttpMethod.GET, "/api", null, null, 1000));
    }

    @Test
    void validTimeoutMillisBoundary() {
        assertDoesNotThrow(() -> new RequestStep("name", HttpMethod.GET, "/api", null, Map.of(), 1));
        assertDoesNotThrow(() -> new RequestStep("name", HttpMethod.GET, "/api", null, Map.of(), 300000));
    }

    @Test
    void invalidTimeoutMillisThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> 
            new RequestStep("name", HttpMethod.GET, "/api", null, Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> 
            new RequestStep("name", HttpMethod.GET, "/api", null, Map.of(), 300001));
    }

    @Test
    void headersMapIsDefensivelyCopied() {
        Map<String, String> originalHeaders = new HashMap<>();
        originalHeaders.put("Key1", "Value1");

        RequestStep step = new RequestStep("name", HttpMethod.GET, "/api", null, originalHeaders, 1000);

        originalHeaders.put("Key2", "Value2");

        assertFalse(step.headers().containsKey("Key2"));
        assertThrows(UnsupportedOperationException.class, () -> step.headers().put("Key3", "Value3"));
    }
}
