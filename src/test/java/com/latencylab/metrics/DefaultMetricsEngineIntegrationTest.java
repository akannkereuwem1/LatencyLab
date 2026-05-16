package com.latencylab.metrics;

import com.latencylab.engine.DefaultVirtualUserEngine;
import com.latencylab.model.HttpMethod;
import com.latencylab.model.MetricsSnapshot;
import com.latencylab.model.RequestStep;
import com.latencylab.model.Scenario;
import com.latencylab.model.VirtualUser;
import com.latencylab.transport.HttpResponseResult;
import com.latencylab.transport.HttpTransportLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMetricsEngineIntegrationTest {

    @Test
    void testEndToEndNxKRequests() {
        HttpTransportLayer stubTransport = step -> new HttpResponseResult(200, null, 1000L);

        DefaultMetricsEngine metricsEngine = new DefaultMetricsEngine();
        DefaultVirtualUserEngine virtualUserEngine = new DefaultVirtualUserEngine(stubTransport, metricsEngine);

        int usersCount = 4;
        int stepsCount = 3;
        Scenario scenario = scenarioWithSteps("nxk", usersCount, stepsCount);

        List<VirtualUser> users = virtualUserEngine.initialize(scenario, usersCount);
        virtualUserEngine.execute(users, scenario);

        MetricsSnapshot snapshot = metricsEngine.snapshot();
        assertEquals((long) usersCount * stepsCount, snapshot.totalRequests());
    }

    @Test
    void testMixedStatusCodeCounting() {
        AtomicInteger counter = new AtomicInteger(0);
        HttpTransportLayer alternatingTransport = step -> {
            int call = counter.getAndIncrement();
            if (call % 2 == 0) {
                return new HttpResponseResult(200, null, 500L);
            }
            return new HttpResponseResult(500, null, 500L);
        };

        DefaultMetricsEngine metricsEngine = new DefaultMetricsEngine();
        DefaultVirtualUserEngine virtualUserEngine = new DefaultVirtualUserEngine(alternatingTransport, metricsEngine);

        int usersCount = 4;
        int stepsCount = 5;
        long totalRequests = (long) usersCount * stepsCount;
        Scenario scenario = scenarioWithSteps("mixed-status", usersCount, stepsCount);

        List<VirtualUser> users = virtualUserEngine.initialize(scenario, usersCount);
        virtualUserEngine.execute(users, scenario);

        MetricsSnapshot snapshot = metricsEngine.snapshot();
        long expectedSuccess = (totalRequests + 1) / 2;
        long expectedFailure = totalRequests / 2;

        assertEquals(totalRequests, snapshot.totalRequests());
        assertEquals(expectedSuccess, snapshot.successfulRequests());
        assertEquals(expectedFailure, snapshot.failedRequests());
    }

    @Test
    void testMinMaxFromTransportResults() {
        long[] latencies = new long[]{100L, 5000L, 300L};
        AtomicInteger index = new AtomicInteger(0);

        HttpTransportLayer cyclingTransport = step -> {
            long latency = latencies[index.getAndIncrement() % latencies.length];
            return new HttpResponseResult(200, null, latency);
        };

        DefaultMetricsEngine metricsEngine = new DefaultMetricsEngine();
        DefaultVirtualUserEngine virtualUserEngine = new DefaultVirtualUserEngine(cyclingTransport, metricsEngine);

        int usersCount = 3;
        int stepsCount = 4;
        Scenario scenario = scenarioWithSteps("min-max", usersCount, stepsCount);

        List<VirtualUser> users = virtualUserEngine.initialize(scenario, usersCount);
        virtualUserEngine.execute(users, scenario);

        MetricsSnapshot snapshot = metricsEngine.snapshot();
        assertEquals(100L, snapshot.minLatencyNanos());
        assertEquals(5000L, snapshot.maxLatencyNanos());
    }

    private Scenario scenarioWithSteps(String name, int usersCount, int stepsCount) {
        List<RequestStep> steps = new ArrayList<>();
        for (int i = 1; i <= stepsCount; i++) {
            steps.add(new RequestStep(
                    "step-" + i,
                    HttpMethod.GET,
                    "/" + name + "/" + i,
                    null,
                    Map.of(),
                    1000
            ));
        }
        return new Scenario(name, steps, 0, 30, usersCount);
    }
}
