package com.latencylab.transport;

import com.latencylab.model.HttpMethod;
import com.latencylab.model.RequestStep;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpTransportLayer implements HttpTransportLayer, Closeable {

    private static final Logger log = LoggerFactory.getLogger(OkHttpTransportLayer.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient client;
    private volatile boolean closed = false;

    public OkHttpTransportLayer(String baseUrl) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
        if (baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        
        String normalizedBaseUrl = baseUrl;
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        this.baseUrl = normalizedBaseUrl;

        this.client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(200, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    private String buildUrl(String endpoint) {
        String normalizedEndpoint = endpoint != null ? endpoint : "";
        while (normalizedEndpoint.startsWith("/")) {
            normalizedEndpoint = normalizedEndpoint.substring(1);
        }
        return baseUrl + "/" + normalizedEndpoint;
    }

    private RequestBody buildBody(String body) {
        if (body == null) {
            return RequestBody.create(new byte[0], JSON);
        }
        return RequestBody.create(body, JSON);
    }

    private Request buildRequest(RequestStep step, String url) {
        Request.Builder builder = new Request.Builder().url(url);

        if (step.headers() != null) {
            for (Map.Entry<String, String> entry : step.headers().entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        RequestBody requestBody = null;
        boolean hasBody = step.body() != null && !step.body().isEmpty();
        if (step.method() == HttpMethod.POST || step.method() == HttpMethod.PUT || step.method() == HttpMethod.PATCH || (step.method() == HttpMethod.DELETE && step.body() != null)) {
            requestBody = buildBody(step.body());
        }

        switch (step.method()) {
            case GET:
                builder.get();
                break;
            case POST:
                builder.post(requestBody);
                break;
            case PUT:
                builder.put(requestBody);
                break;
            case PATCH:
                builder.patch(requestBody);
                break;
            case DELETE:
                if (step.body() != null) {
                    builder.delete(requestBody);
                } else {
                    builder.delete();
                }
                break;
        }

        boolean hasContentType = step.headers() != null && step.headers().keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Content-Type"));
        if (hasBody && !hasContentType) {
            builder.header("Content-Type", "application/json; charset=utf-8");
        }

        return builder.build();
    }

    @Override
    public HttpResponseResult execute(RequestStep step) {
        Objects.requireNonNull(step, "step must not be null");
        if (closed) {
            throw new IllegalStateException("Transport layer is closed");
        }

        String url = buildUrl(step.endpoint());
        Request request = buildRequest(step, url);

        log.debug("Dispatching request step '{}': {} {}", step.name(), request.method(), request.url());

        OkHttpClient callClient = client;
        if (step.timeoutMillis() > 0) {
            callClient = client.newBuilder()
                    .readTimeout(step.timeoutMillis(), TimeUnit.MILLISECONDS)
                    .build();
        }

        okhttp3.Call call = callClient.newCall(request);
        long startNanos = System.nanoTime();
        try (Response response = call.execute()) {
            long endNanos = System.nanoTime();
            int statusCode = response.code();
            ResponseBody responseBody = response.body();
            String bodyString = null;
            if (responseBody != null) {
                byte[] bytes = responseBody.bytes();
                if (bytes.length > 0) {
                    bodyString = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            long latencyNanos = endNanos - startNanos;
            log.debug("Completed request step '{}': status {}, latency {}ns", step.name(), statusCode, latencyNanos);
            return new HttpResponseResult(statusCode, bodyString, latencyNanos);
        } catch (java.io.IOException e) {
            long endNanos = System.nanoTime();
            long latencyNanos = endNanos - startNanos;
            log.debug("Failed request step '{}': {}", step.name(), e.getMessage());
            return new HttpResponseResult(0, null, latencyNanos);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
