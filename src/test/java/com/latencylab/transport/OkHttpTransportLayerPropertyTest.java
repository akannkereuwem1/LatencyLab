package com.latencylab.transport;

import com.latencylab.model.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.time.api.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OkHttpTransportLayerPropertyTest {

    private OkHttpTransportLayer createTransport(String baseUrl) {
        return new OkHttpTransportLayer(baseUrl);
    }

    // Helper to invoke private buildUrl via reflection
    private String invokeBuildUrl(OkHttpTransportLayer transport, String endpoint) throws Exception {
        Method m = OkHttpTransportLayer.class.getDeclaredMethod("buildUrl", String.class);
        m.setAccessible(true);
        return (String) m.invoke(transport, endpoint);
    }

    // Helper to invoke private buildBody via reflection
    private okhttp3.RequestBody invokeBuildBody(OkHttpTransportLayer transport, String body) throws Exception {
        Method m = OkHttpTransportLayer.class.getDeclaredMethod("buildBody", String.class);
        m.setAccessible(true);
        return (okhttp3.RequestBody) m.invoke(transport, body);
    }

    @Property(tries = 100)
    void property_urlConstructionProducesSingleSlash(@ForAll @StringLength(min = 1, max = 20) String rawBase,
                                                    @ForAll @StringLength(min = 0, max = 20) String rawEndpoint,
                                                    @ForAll("slashVariants") boolean baseHasSlash,
                                                    @ForAll("slashVariants") boolean endpointHasSlash) throws Exception {
        // Create baseUrl possibly with trailing slash
        String base = baseHasSlash ? rawBase.endsWith("/") ? rawBase : rawBase + "/" : rawBase.replaceAll("/+$", "");
        // Ensure a valid http scheme for the test (the transport expects a URL, we can prepend http://)
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        // Endpoint possibly with leading slash
        String endpoint = endpointHasSlash ? (rawEndpoint.startsWith("/") ? rawEndpoint : "/" + rawEndpoint) : rawEndpoint.replaceAll("^/", "");
        OkHttpTransportLayer transport = createTransport(base);
        String full = invokeBuildUrl(transport, endpoint);
        // After normalization there must be exactly one '/' between base and endpoint
        String[] parts = full.split("//")[1].split("/"); // split after protocol
        // The first part after protocol is the host, the rest are path segments
        // Reconstruct path part
        String path = full.substring(full.indexOf("//") + 2);
        int firstSlash = path.indexOf('/');
        String afterHost = firstSlash >= 0 ? path.substring(firstSlash) : "";
        // Count consecutive slashes at the start of afterHost
        int slashCount = 0;
        for (char c : afterHost.toCharArray()) {
            if (c == '/') slashCount++; else break;
        }
        assertTrue(slashCount == 1, "There should be exactly one separator slash");
    }

    @Provide
    Arbitrary<Boolean> slashVariants() {
        return Arbitraries.of(true, false);
    }

    @Property(tries = 100)
    void property_bodyRoundTrip(@ForAll("nonNullStrings") String body,
                               @ForAll("httpMethods") HttpMethod method) throws Exception {
        // Only test POST, PUT, PATCH as per spec
        Assume.that(method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH);
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200));
        server.start();
        OkHttpTransportLayer transport = createTransport(server.url("/").toString());
        RequestStep step = new RequestStep("prop-body", method, "/test", body, Collections.emptyMap(), 0);
        transport.execute(step);
        RecordedRequest recorded = server.takeRequest();
        assertEquals(body, recorded.getBody().readUtf8());
        server.shutdown();
    }

    @Provide
    Arbitrary<String> nonNullStrings() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<HttpMethod> httpMethods() {
        return Arbitraries.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);
    }

    @Property(tries = 100)
    void property_headerForwarding(@ForAll("headerMaps") Map<String, String> headers) throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200));
        server.start();
        OkHttpTransportLayer transport = createTransport(server.url("/").toString());
        RequestStep step = new RequestStep("prop-header", HttpMethod.GET, "/h", null, headers, 0);
        transport.execute(step);
        RecordedRequest recorded = server.takeRequest();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            assertEquals(e.getValue(), recorded.getHeader(e.getKey()));
        }
        server.shutdown();
    }

    @Provide
    Arbitrary<Map<String, String>> headerMaps() {
        return Arbitraries.maps(Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(5),
                                 Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
                                 0, 5);
    }

    @Property(tries = 100)
    void property_responseFieldPreservation(@ForAll("statusCodes") int status,
                                            @ForAll("bodies") String body) throws Exception {
        Assume.that(status >= 200 && status < 300);
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(status).setBody(body));
        server.start();
        OkHttpTransportLayer transport = createTransport(server.url("/").toString());
        RequestStep step = new RequestStep("prop-resp", HttpMethod.GET, "/r", null, Collections.emptyMap(), 0);
        HttpResponseResult result = transport.execute(step);
        assertEquals(status, result.statusCode());
        assertEquals(body, result.responseBody());
        server.shutdown();
    }

    @Provide
    Arbitrary<Integer> statusCodes() {
        return Arbitraries.integers().between(200, 299);
    }

    @Provide
    Arbitrary<String> bodies() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(30);
    }

    @Property(tries = 100)
    void property_latencyNonNegative(@ForAll("bools") boolean causeFailure) throws Exception {
        MockWebServer server = new MockWebServer();
        if (causeFailure) {
            server.shutdown(); // cause network failure
        } else {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
        }
        OkHttpTransportLayer transport = createTransport(server.url("/").toString());
        RequestStep step = new RequestStep("prop-latency", HttpMethod.GET, "/l", null, Collections.emptyMap(), 0);
        HttpResponseResult result = transport.execute(step);
        assertTrue(result.latencyNanos() >= 0);
        if (!causeFailure) {
            server.shutdown();
        }
    }

    @Provide
    Arbitrary<Boolean> bools() {
        return Arbitraries.of(true, false);
    }

    @Property(tries = 100)
    void property_blankBaseUrlThrows(@ForAll("blankStrings") String blank) {
        Assume.that(blank.trim().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new OkHttpTransportLayer(blank));
    }

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.strings().withChars(' ', '\t', '\n').ofMinLength(1).ofMaxLength(5);
    }

    @Property(tries = 100)
    void property_closeIdempotent(@ForAll("counts") @IntRange(min = 2, max = 10) int times) {
        MockWebServer server = new MockWebServer();
        server.start();
        OkHttpTransportLayer transport = createTransport(server.url("/").toString());
        for (int i = 0; i < times; i++) {
            transport.close();
        }
        server.shutdown();
    }
}
