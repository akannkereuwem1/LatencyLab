package com.latencylab.engine;

import com.latencylab.model.Scenario;
import com.latencylab.model.RequestStep;
import com.latencylab.model.HttpMethod;
import com.latencylab.model.VirtualUser;
import com.latencylab.transport.HttpTransportLayer;
import com.latencylab.transport.HttpResponseResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test class for DefaultVirtualUserEngine.
 */
class DefaultVirtualUserEngineTest {

    private Scenario testScenario;
    private RequestStep testStep;

    @BeforeEach
    void setUp() {
        // Create a simple scenario with one step for most tests
        testStep = new RequestStep("test-step", HttpMethod.GET, "/test", null, Map.of(), 1000);
        List<RequestStep> steps = new ArrayList<>();
        steps.add(testStep);
        testScenario = new Scenario("test-scenario", steps, 0, 30, 1);
    }

    /**
     * Inner class that counts invocations per step name.
     */
    static class CountingTransport implements HttpTransportLayer {
        private final Map<String, AtomicInteger> invocationCounts = new ConcurrentHashMap<>();
        private final HttpResponseResult responseToReturn;

        CountingTransport(HttpResponseResult responseToReturn) {
            this.responseToReturn = responseToReturn;
        }

        public HttpResponseResult execute(RequestStep step) {
            invocationCounts.computeIfAbsent(step.name(), k -> new AtomicInteger(0)).incrementAndGet();
            return responseToReturn;
        }

        public int getInvocationCount(String stepName) {
            AtomicInteger count = invocationCounts.get(stepName);
            return count != null ? count.get() : 0;
        }

        public Map<String, Integer> getInvocationCounts() {
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, AtomicInteger> entry : invocationCounts.entrySet()) {
                result.put(entry.getKey(), entry.getValue().get());
            }
            return result;
        }

        public void close() {
            // No-op for test
        }
    }

    /**
     * Inner class that always throws an exception.
     */
    static class FailingTransport implements HttpTransportLayer {
        private final RuntimeException exceptionToThrow;

        FailingTransport(RuntimeException exceptionToThrow) {
            this.exceptionToThrow = exceptionToThrow;
        }

        public HttpResponseResult execute(RequestStep step) {
            throw exceptionToThrow;
        }

        public void close() {
            // No-op for test
        }
    }

    @Test
    void initialize_returnsCorrectListSize() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));

        List<VirtualUser> users = engine.initialize(testScenario, 5);
        Assertions.assertEquals(5, users.size());
    }

    @Test
    void initialize_elementFields_correct() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));

        List<VirtualUser> users = engine.initialize(testScenario, 3);
        Assertions.assertEquals(3, users.size());

        for (int i = 0; i < users.size(); i++) {
            VirtualUser user = users.get(i);
            Assertions.assertEquals("user-" + (i + 1), user.userId());
            Assertions.assertEquals(com.latencylab.model.VirtualUserState.IDLE, user.state());
            Assertions.assertEquals(testScenario, user.activeScenario());
            Assertions.assertNull(user.metricsSnapshot());
        }
    }

    @Test
    void initialize_listIsUnmodifiable() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));

        List<VirtualUser> users = engine.initialize(testScenario, 2);
        Assertions.assertThrows(java.lang.UnsupportedOperationException.class, () -> {
            users.add(new VirtualUser("dummy", com.latencylab.model.VirtualUserState.IDLE, testScenario, null));
        });
    }

    @Test
    void initialize_userCountZero_throwsIAE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            engine.initialize(testScenario, 0);
        });
    }

    @Test
    void initialize_userCountNegative_throwsIAE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            engine.initialize(testScenario, -1);
        });
    }

    @Test
    void initialize_nullScenario_throwsNPE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));
        Assertions.assertThrows(NullPointerException.class, () -> {
            engine.initialize(null, 5);
        });
    }

    @Test
    void execute_invokesTransportOncePerStepPerUser() {
        CountingTransport transport = new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport);

        // Create a scenario with 2 steps
        RequestStep step1 = new RequestStep("step1", HttpMethod.GET, "/step1", null, Map.of(), 1000);
        RequestStep step2 = new RequestStep("step2", HttpMethod.POST, "/step2", null, Map.of(), 1000);
        List<RequestStep> steps = List.of(step1, step2);
        Scenario scenario = new Scenario("multi-step", steps, 0, 30, 3);

        // Initialize 3 users
        List<VirtualUser> users = engine.initialize(scenario, 3);

        // Execute
        engine.execute(users, scenario);

        // Each user should have executed each step once
        Assertions.assertEquals(3, transport.getInvocationCount("step1"));
        Assertions.assertEquals(3, transport.getInvocationCount("step2"));
    }

    @Test
    void execute_emptyUsers_returnsImmediately() {
        CountingTransport transport = new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport);

        // Execute with empty list - should not throw and not invoke transport
        engine.execute(new ArrayList<>(), testScenario);

        // Invocation count should be zero
        Assertions.assertEquals(0, transport.getInvocationCount("test-step"));
    }

    @Test
    void execute_nullUsers_throwsNPE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));
        Assertions.assertThrows(NullPointerException.class, () -> {
            engine.execute(null, testScenario);
        });
    }

    @Test
    void execute_nullScenario_throwsNPE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(new CountingTransport(
                new HttpResponseResult(200, "ok", 1000L)));
        List<VirtualUser> users = engine.initialize(testScenario, 1);
        Assertions.assertThrows(NullPointerException.class, () -> {
            engine.execute(users, null);
        });
    }

    @Test
    void execute_failingTransport_doesNotPropagateException() {
        FailingTransport transport = new FailingTransport(
                new RuntimeException("simulated failure"));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport);

        // Initialize 1 user
        List<VirtualUser> users = engine.initialize(testScenario, 1);

        // Execute should not throw even though transport fails
        Assertions.assertDoesNotThrow(() -> {
            engine.execute(users, testScenario);
        });
    }

    @Test
    void execute_failingTransport_otherUsersComplete_approximation() {
        // This test verifies that when one user fails, the engine continues processing
        // We approximate this by having a transport that fails on the first few calls
        // then succeeds (simulating different users having different outcomes)
        
         class EventuallySucceedingTransport implements HttpTransportLayer {
             private final int failCount;
             private final AtomicInteger callCount = new AtomicInteger(0);

             EventuallySucceedingTransport(int failCount) {
                 this.failCount = failCount;
             }

             public HttpResponseResult execute(RequestStep step) {
                 int currentCall = callCount.incrementAndGet();
                 if (currentCall <= failCount) {
                     throw new RuntimeException("simulated failure on call " + currentCall);
                 }
                 // Succeed after the specified number of failures.
                 return new HttpResponseResult(200, "ok", 1000L);
             }

             public void close() {
                 // No-op
             }
         }

        // Configure to fail on first call, succeed on second and third
        // This simulates: user1 fails, user2 succeeds, user3 succeeds (assuming sequential execution for simplicity)
        EventuallySucceedingTransport transport = new EventuallySucceedingTransport(1);
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport);

        // Initialize 3 users
        List<VirtualUser> users = engine.initialize(testScenario, 3);

        // Execute - should not throw because engine catches exceptions
        Assertions.assertDoesNotThrow(() -> {
            engine.execute(users, testScenario);
        });

        // Verify that we made at least 3 calls (one per user)
        // Due to virtual thread scheduling, exact counts may vary but should be >= 3
        Assertions.assertTrue(transport.callCount.get() >= 3, 
            "Expected at least 3 calls but got " + transport.callCount.get());
    }
}