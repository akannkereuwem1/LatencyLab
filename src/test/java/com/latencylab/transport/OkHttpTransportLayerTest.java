package com.latencylab.transport;

import com.latencylab.model.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OkHttpTransportLayerTest {
    private static final Logger log = LoggerFactory.getLogger(OkHttpTransportLayerTest.class);
    private MockWebServer mockWebServer;
    private OkHttpTransportLayer transport;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        transport = new OkHttpTransportLayer(baseUrl);
    }

    @AfterEach
    void tearDown() throws Exception {
        transport.close();
        mockWebServer.shutdown();
    }

    private RequestStep step(String name, HttpMethod method, String endpoint, String body, Map<String, String> headers, int timeoutMillis) {
        // Ensure headers is non-null because RequestStep validates it
        if (headers == null) {
            headers = java.util.Collections.emptyMap();
        }
        return new RequestStep(name, method, endpoint, body, headers, timeoutMillis);

    }

    @Test
    void getRequest_returnsStatusAndBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("hello"));
        RequestStep rs = step("get-test", HttpMethod.GET, "/test", null, null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(200, result.statusCode());
        assertEquals("hello", result.responseBody());
        assertTrue(result.latencyNanos() >= 1);
        var recorded = mockWebServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/test", recorded.getPath());
    }

    @Test
    void postRequest_withBody_sendsBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(201));
        RequestStep rs = step("post-test", HttpMethod.POST, "/post", "{\"msg\":\"hi\"}", null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(201, result.statusCode());
        var recorded = mockWebServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("{\"msg\":\"hi\"}", recorded.getBody().readUtf8());
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
    }

    @Test
    void postRequest_nullBody_sendsEmptyBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        RequestStep rs = step("post-null", HttpMethod.POST, "/null", null, null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(200, result.statusCode());
        var recorded = mockWebServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals(0, recorded.getBodySize());
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
    }

    @Test
    void deleteRequest_noBody_sendsNoBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(204));
        RequestStep rs = step("del-no-body", HttpMethod.DELETE, "/del", null, null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(204, result.statusCode());
        var recorded = mockWebServer.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
        assertEquals(0, recorded.getBodySize());
    }

    @Test
    void deleteRequest_withBody_sendsBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        RequestStep rs = step("del-body", HttpMethod.DELETE, "/del", "payload", null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(200, result.statusCode());
        var recorded = mockWebServer.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
        assertEquals("payload", recorded.getBody().readUtf8());
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
    }

    @Test
    void putRequest_withBody_sendsBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        RequestStep rs = step("put-test", HttpMethod.PUT, "/put", "payload", null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(200, result.statusCode());
        var recorded = mockWebServer.takeRequest();
        assertEquals("PUT", recorded.getMethod());
        assertEquals("payload", recorded.getBody().readUtf8());
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
    }

    @Test
    void patchRequest_withBody_sendsBody() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        RequestStep rs = step("patch-test", HttpMethod.PATCH, "/patch", "payload", null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(200, result.statusCode());
        var recorded = mockWebServer.takeRequest();
        assertEquals("PATCH", recorded.getMethod());
        assertEquals("payload", recorded.getBody().readUtf8());
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"));
    }

    @Test
    void networkFailure_returnsStatusZero() throws Exception {
        mockWebServer.shutdown(); // force connection failure
        RequestStep rs = step("fail", HttpMethod.GET, "/fail", null, null, 0);
        HttpResponseResult result = transport.execute(rs);
        assertEquals(0, result.statusCode());
        assertNull(result.responseBody());
        assertTrue(result.latencyNanos() >= 1);
    }

    @Test
    void nullStep_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> transport.execute(null));
    }

    @Test
    void executeAfterClose_throwsIllegalStateException() throws Exception {
        transport.close();
        RequestStep rs = step("after-close", HttpMethod.GET, "/x", null, null, 0);
        assertThrows(IllegalStateException.class, () -> transport.execute(rs));
    }

    @Test
    void nullBaseUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new OkHttpTransportLayer(null));
    }

    @Test
    void blankBaseUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new OkHttpTransportLayer("   "));
    }
}
