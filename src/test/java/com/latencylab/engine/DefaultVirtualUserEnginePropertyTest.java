package com.latencylab.engine;

import com.latencylab.model.*;
import com.latencylab.metrics.MetricsEngine;
import com.latencylab.model.MetricsSnapshot;
import com.latencylab.transport.HttpTransportLayer;
import com.latencylab.transport.HttpResponseResult;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for DefaultVirtualUserEngine.
 */
class DefaultVirtualUserEnginePropertyTest {
    
     /** No-op MetricsEngine for testing */
     static class NoOpMetricsEngine implements MetricsEngine {
         @Override
         public void record(long latencyNanos, boolean success) {
             // No-op
         }
 
         @Override
         public MetricsSnapshot snapshot() {
             return null;
         }
     }

    // Helper to create a scenario with given step count
    private Scenario createScenario(int stepCount) {
        List<RequestStep> steps = IntStream.range(0, stepCount)
                .mapToObj(i -> new RequestStep(
                        "step-" + i,
                        HttpMethod.GET,
                        "/endpoint-" + i,
                        null,
                        Map.of(),
                        1000))
                .collect(Collectors.toList());
        return new Scenario("test-scenario", steps, 0, 30, stepCount);
    }

    // Transport that records invocations for property testing
    static class RecordingTransport implements HttpTransportLayer {
        private final List<String> invokedSteps = new CopyOnWriteArrayList<>();
        private final HttpResponseResult resultToReturn;

        RecordingTransport(HttpResponseResult resultToReturn) {
            this.resultToReturn = resultToReturn;
        }

        @Override
        public HttpResponseResult execute(RequestStep step) {
            invokedSteps.add(step.name());
            return resultToReturn;
        }

        public List<String> getInvokedSteps() {
            return new ArrayList<>(invokedSteps);
        }

         public Map<String, Long> getInvocationCounts() {
             return invokedSteps.stream()
                     .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
         }

         public void close() {
             // No-op
         }
    }

    // Transport that fails for specific userIds (approximated by call count)
    static class SelectiveFailingTransport implements HttpTransportLayer {
        private final int failAtCall;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final HttpResponseResult successResult;

        SelectiveFailingTransport(int failAtCall, HttpResponseResult successResult) {
            this.failAtCall = failAtCall;
            this.successResult = successResult;
        }

        @Override
        public HttpResponseResult execute(RequestStep step) {
            int currentCall = callCount.incrementAndGet();
            if (currentCall == failAtCall) {
                throw new RuntimeException("Simulated failure at call " + currentCall);
            }
            return successResult;
        }

        public int getTotalCallCount() {
            return callCount.get();
        }

         public void close() {
             // No-op
         }
    }

    @Property
    void initializeReturnsCorrectlySizedListForAnyValidUserCount(
            @ForAll @IntRange(min = 1, max = 1000) int userCount,
            @ForAll @IntRange(min = 1, max = 10) int stepCount) {
        
        Scenario scenario = createScenario(stepCount);
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new RecordingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(scenario, userCount);

        // Property: initialize returns a list with size equal to userCount
        assertEquals(userCount, users.size());

        // Property: each element has correct structure
        for (int i = 0; i < userCount; i++) {
            VirtualUser user = users.get(i);
            assertEquals("user-" + (i + 1), user.userId());
            assertEquals(com.latencylab.model.VirtualUserState.IDLE, user.state());
            assertEquals(scenario, user.activeScenario());
            assertNull(user.metricsSnapshot());
        }

        // Property: returned list is unmodifiable
        assertThrows(java.lang.UnsupportedOperationException.class, () -> {
            users.add(new VirtualUser(
                    "dummy", com.latencylab.model.VirtualUserState.IDLE, scenario, null));
        });
    }

    @Property
    void perUserExceptionIsolationFailingUsersDoNotAffectOthers(
            @ForAll @IntRange(min = 2, max = 20) int totalUserCount,
            @ForAll @IntRange(min = 1, max = 5) int failingUserIndex) {
        
        // Ensure failingUserIndex is within bounds
        int adjustedFailingIndex = Math.min(failingUserIndex, totalUserCount - 1);
        
        Scenario scenario = createScenario(3); // 3 steps per user
        HttpResponseResult successResult = new HttpResponseResult(200, "ok", 1000L);
        
         // Transport that fails for a specific user (approximated by call count)
         // We'll make it fail on the first step of the targeted user
         SelectiveFailingTransport transport = new SelectiveFailingTransport(
                 adjustedFailingIndex * 3 + 1, // Fail on first step of failing user
                 successResult);
         
         DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport, new NoOpMetricsEngine());

        // Initialize users
        List<VirtualUser> users = engine.initialize(scenario, totalUserCount);

        // Execute - should not throw because engine catches exceptions
        assertDoesNotThrow(() -> {
            engine.execute(users, scenario);
        });

        // Property: engine should not propagate exceptions from failing users
        // (verified by the fact that execute() didn't throw)

        // Property: count invocations to verify non-failing users completed their steps
        int totalInvocations = transport.getTotalCallCount();
        
        // Each user should have attempted their steps (some may have failed mid-way)
        // At minimum, we should have calls for all users' steps up to the point of failure
        // We should have at least (totalUserCount - 1) * 3 successful invocations
        // plus however many the failing user got before failing (at least 0)
        long expectedMinSuccessful = (totalUserCount - 1) * 3L;
        assertTrue(totalInvocations >= expectedMinSuccessful, 
                "Expected at least " + expectedMinSuccessful + " invocations but got " + totalInvocations);
    }

    @Property
    void initializeReturnsCorrectlyStructuredList(
            @ForAll @IntRange(min = 1, max = 100) int userCount,
            @ForAll @IntRange(min = 1, max = 5) int stepCount) {
        
        Scenario scenario = createScenario(stepCount);
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new RecordingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(scenario, userCount);

        // Property: list size equals userCount
        assertEquals(userCount, users.size());

        // Property: every element at index i has correct values
        for (int i = 0; i < userCount; i++) {
            VirtualUser user = users.get(i);
            assertEquals("user-" + (i + 1), user.userId());
            assertEquals(com.latencylab.model.VirtualUserState.IDLE, user.state());
            assertEquals(scenario, user.activeScenario());
            assertNull(user.metricsSnapshot());
        }

        // Property: list is unmodifiable
        assertThrows(java.lang.UnsupportedOperationException.class, () -> {
            users.add(
                    new VirtualUser("dummy", com.latencylab.model.VirtualUserState.IDLE, scenario, null));
        });
    }
}