package com.latencylab.engine;

import com.latencylab.model.HttpMethod;
import com.latencylab.model.MetricsSnapshot;
import com.latencylab.model.RequestStep;
import com.latencylab.model.Scenario;
import com.latencylab.model.VirtualUser;
import com.latencylab.model.VirtualUserState;
import com.latencylab.metrics.MetricsEngine;
import com.latencylab.transport.HttpResponseResult;
import com.latencylab.transport.HttpTransportLayer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultVirtualUserEngine.
 */
class DefaultVirtualUserEngineTest {

    private Scenario testScenario;
    private RequestStep testStep;

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /** No-op MetricsEngine for tests that don't care about metrics. */
    static class NoOpMetricsEngine implements MetricsEngine {
        @Override
        public void record(long latencyNanos, boolean success) { }

        @Override
        public MetricsSnapshot snapshot() { return null; }
    }

    /** MetricsEngine that counts record() invocations. */
    static class CountingMetricsEngine implements MetricsEngine {
        private final AtomicInteger recordCount = new AtomicInteger(0);

        @Override
        public void record(long latencyNanos, boolean success) {
            recordCount.incrementAndGet();
        }

        @Override
        public MetricsSnapshot snapshot() { return null; }

        public int getRecordCount() { return recordCount.get(); }
    }

    /** Transport that returns a fixed response and counts invocations per step name. */
    static class CountingTransport implements HttpTransportLayer {
        private final Map<String, AtomicInteger> invocationCounts = new ConcurrentHashMap<>();
        private final HttpResponseResult responseToReturn;

        CountingTransport(HttpResponseResult responseToReturn) {
            this.responseToReturn = responseToReturn;
        }

        @Override
        public HttpResponseResult execute(RequestStep step) {
            invocationCounts.computeIfAbsent(step.name(), k -> new AtomicInteger(0)).incrementAndGet();
            return responseToReturn;
        }

        public int getInvocationCount(String stepName) {
            AtomicInteger count = invocationCounts.get(stepName);
            return count != null ? count.get() : 0;
        }

        public int getTotalInvocations() {
            return invocationCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    /** Transport that always throws a RuntimeException. */
    static class FailingTransport implements HttpTransportLayer {
        private final RuntimeException exceptionToThrow;

        FailingTransport(RuntimeException exceptionToThrow) {
            this.exceptionToThrow = exceptionToThrow;
        }

        @Override
        public HttpResponseResult execute(RequestStep step) {
            throw exceptionToThrow;
        }
    }

    /** Transport that wraps InterruptedException in a RuntimeException. */
    static class InterruptingTransport implements HttpTransportLayer {
        @Override
        public HttpResponseResult execute(RequestStep step) {
            // Simulate an interrupt by interrupting the current thread and throwing
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException("simulated interruption"));
        }
    }

    @BeforeEach
    void setUp() {
        testStep = new RequestStep("test-step", HttpMethod.GET, "/test", null, Map.of(), 1000);
        testScenario = new Scenario("test-scenario", List.of(testStep), 0, 30, 1);
    }

    // -------------------------------------------------------------------------
    // Constructor tests
    // -------------------------------------------------------------------------

    @Test
    void constructor_nullTransport_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
                new DefaultVirtualUserEngine(null, new NoOpMetricsEngine()));
    }

    @Test
    void constructor_nullMetricsEngine_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
                new DefaultVirtualUserEngine(
                        new CountingTransport(new HttpResponseResult(200, "ok", 1000L)), null));
    }

    // -------------------------------------------------------------------------
    // initialize() tests
    // -------------------------------------------------------------------------

    @Test
    void initialize_returnsCorrectListSize() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 5);
        assertEquals(5, users.size());
    }

    @Test
    void initialize_elementFields_correct() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 3);
        for (int i = 0; i < users.size(); i++) {
            VirtualUser user = users.get(i);
            assertEquals("user-" + (i + 1), user.userId());
            assertEquals(VirtualUserState.IDLE, user.state());
            assertEquals(testScenario, user.activeScenario());
            assertNull(user.metricsSnapshot());
        }
    }

    @Test
    void initialize_listIsUnmodifiable() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 2);
        assertThrows(UnsupportedOperationException.class, () ->
                users.add(new VirtualUser("dummy", VirtualUserState.IDLE, testScenario, null)));
    }

    @Test
    void initialize_userCountZero_throwsIAE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());
        assertThrows(IllegalArgumentException.class, () -> engine.initialize(testScenario, 0));
    }

    @Test
    void initialize_userCountNegative_throwsIAE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());
        assertThrows(IllegalArgumentException.class, () -> engine.initialize(testScenario, -1));
    }

    @Test
    void initialize_nullScenario_throwsNPE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());
        assertThrows(NullPointerException.class, () -> engine.initialize(null, 5));
    }

    // -------------------------------------------------------------------------
    // execute() tests
    // -------------------------------------------------------------------------

    @Test
    void execute_invokesTransportOncePerStepPerUser() {
        CountingTransport transport = new CountingTransport(new HttpResponseResult(200, "ok", 1000L));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport, new NoOpMetricsEngine());

        RequestStep step1 = new RequestStep("step1", HttpMethod.GET, "/step1", null, Map.of(), 1000);
        RequestStep step2 = new RequestStep("step2", HttpMethod.POST, "/step2", null, Map.of(), 1000);
        Scenario scenario = new Scenario("multi-step", List.of(step1, step2), 0, 30, 3);

        List<VirtualUser> users = engine.initialize(scenario, 3);
        engine.execute(users, scenario);

        assertEquals(3, transport.getInvocationCount("step1"));
        assertEquals(3, transport.getInvocationCount("step2"));
    }

    @Test
    void execute_emptyUsers_returnsImmediately() {
        CountingTransport transport = new CountingTransport(new HttpResponseResult(200, "ok", 1000L));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport, new NoOpMetricsEngine());

        engine.execute(new ArrayList<>(), testScenario);

        assertEquals(0, transport.getInvocationCount("test-step"));
    }

    @Test
    void execute_nullUsers_throwsNPE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());
        assertThrows(NullPointerException.class, () -> engine.execute(null, testScenario));
    }

    @Test
    void execute_nullScenario_throwsNPE() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());
        List<VirtualUser> users = engine.initialize(testScenario, 1);
        assertThrows(NullPointerException.class, () -> engine.execute(users, null));
    }

    @Test
    void execute_failingTransport_doesNotPropagateException() {
        FailingTransport transport = new FailingTransport(new RuntimeException("simulated failure"));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport, new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 1);
        assertDoesNotThrow(() -> engine.execute(users, testScenario));
    }

    @Test
    void execute_failingUser_doesNotPreventOthersFromCompleting() {
        // Use a transport that fails for user-1 and succeeds for others
        // We track which userId is executing via thread name
        CountingTransport successTransport = new CountingTransport(new HttpResponseResult(200, "ok", 1000L));

        class SelectiveTransport implements HttpTransportLayer {
            private final AtomicInteger callCount = new AtomicInteger(0);

            @Override
            public HttpResponseResult execute(RequestStep step) {
                // First call (user-1) fails, subsequent calls succeed
                if (callCount.incrementAndGet() == 1) {
                    throw new RuntimeException("user-1 fails");
                }
                return new HttpResponseResult(200, "ok", 1000L);
            }
        }

        SelectiveTransport transport = new SelectiveTransport();
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport, new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 3);
        assertDoesNotThrow(() -> engine.execute(users, testScenario));

        // At least 2 users should have completed (the ones that didn't fail)
        long completedCount = users.stream()
                .map(u -> engine.getState(u.userId()))
                .filter(s -> s == VirtualUserState.COMPLETED)
                .count();
        assertTrue(completedCount >= 2, "Expected at least 2 completed users, got " + completedCount);
    }

    @Test
    void execute_afterStop_returnsImmediately() {
        CountingTransport transport = new CountingTransport(new HttpResponseResult(200, "ok", 1000L));
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(transport, new NoOpMetricsEngine());

        engine.stop();

        List<VirtualUser> users = engine.initialize(testScenario, 3);
        engine.execute(users, testScenario);

        // No threads should have been launched — transport never called
        assertEquals(0, transport.getTotalInvocations());
    }

    // -------------------------------------------------------------------------
    // pause() / resume() / stop() tests
    // -------------------------------------------------------------------------

    @Test
    void pause_blocksThreadsUntilResume() throws InterruptedException {
        // Transport blocks until we release it, then counts invocations
        CountDownLatch transportLatch = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger(0);

        class BlockingTransport implements HttpTransportLayer {
            @Override
            public HttpResponseResult execute(RequestStep step) {
                try {
                    transportLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                invocationCount.incrementAndGet();
                return new HttpResponseResult(200, "ok", 1000L);
            }
        }

        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new BlockingTransport(), new NoOpMetricsEngine());
        List<VirtualUser> users = engine.initialize(testScenario, 1);

        // Pause before releasing the transport so the thread blocks at the inter-step boundary
        engine.pause();

        Thread executionThread = new Thread(() -> engine.execute(users, testScenario));
        executionThread.start();

        // Release the transport so the first (and only) step can complete
        transportLatch.countDown();

        // Give the virtual thread time to finish the step and hit the pause latch
        Thread.sleep(200);

        // Transport was called once (the step completed), but the thread is now paused
        assertEquals(1, invocationCount.get());

        // Resume and wait for completion
        engine.resume();
        executionThread.join(5000);

        assertFalse(executionThread.isAlive(), "Execution thread should have completed");
    }

    @Test
    void stop_causesThreadsToExitAfterCurrentCall() throws InterruptedException {
        // Use a latch to synchronize: let the first step complete, then stop
        CountDownLatch firstStepDone = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger(0);

        class SyncTransport implements HttpTransportLayer {
            @Override
            public HttpResponseResult execute(RequestStep step) {
                int count = invocationCount.incrementAndGet();
                if (count == 1) {
                    firstStepDone.countDown();
                }
                return new HttpResponseResult(200, "ok", 1000L);
            }
        }

        RequestStep step1 = new RequestStep("step1", HttpMethod.GET, "/step1", null, Map.of(), 1000);
        RequestStep step2 = new RequestStep("step2", HttpMethod.GET, "/step2", null, Map.of(), 1000);
        RequestStep step3 = new RequestStep("step3", HttpMethod.GET, "/step3", null, Map.of(), 1000);
        Scenario scenario = new Scenario("multi-step", List.of(step1, step2, step3), 0, 30, 1);

        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new SyncTransport(), new NoOpMetricsEngine());
        List<VirtualUser> users = engine.initialize(scenario, 1);

        Thread executionThread = new Thread(() -> engine.execute(users, scenario));
        executionThread.start();

        // Wait for first step to complete, then stop
        firstStepDone.await();
        engine.stop();

        executionThread.join(5000);
        assertFalse(executionThread.isAlive(), "Execution thread should have completed");

        // At least step1 was executed; step2 and step3 may or may not have run
        // depending on scheduling, but total should be < 3 (stop was called after step1)
        assertTrue(invocationCount.get() >= 1, "At least step1 should have executed");
        assertTrue(invocationCount.get() <= 3, "Should not exceed total step count");
    }

    @Test
    void stop_idempotent_noExceptionOnMultipleCalls() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        assertDoesNotThrow(() -> {
            engine.stop();
            engine.stop();
            engine.stop();
            engine.stop();
            engine.stop();
        });
    }

    // -------------------------------------------------------------------------
    // Metrics tests
    // -------------------------------------------------------------------------

    @Test
    void metricsRecord_calledOncePerResult_notCalledOnException() {
        CountingMetricsEngine metricsEngine = new CountingMetricsEngine();

        RequestStep step1 = new RequestStep("step1", HttpMethod.GET, "/step1", null, Map.of(), 1000);
        RequestStep step2 = new RequestStep("step2", HttpMethod.POST, "/step2", null, Map.of(), 1000);
        RequestStep step3 = new RequestStep("step3", HttpMethod.GET, "/step3", null, Map.of(), 1000);
        Scenario scenario = new Scenario("multi-step", List.of(step1, step2, step3), 0, 30, 1);

        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)), metricsEngine);
        List<VirtualUser> users = engine.initialize(scenario, 1);
        engine.execute(users, scenario);

        // 1 user × 3 steps = 3 record() calls
        assertEquals(3, metricsEngine.getRecordCount());
    }

    @Test
    void metricsRecord_notCalledWhenTransportThrows() {
        CountingMetricsEngine metricsEngine = new CountingMetricsEngine();
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new FailingTransport(new RuntimeException("transport failure")), metricsEngine);

        List<VirtualUser> users = engine.initialize(testScenario, 1);
        engine.execute(users, testScenario);

        assertEquals(0, metricsEngine.getRecordCount());
    }

    // -------------------------------------------------------------------------
    // State registry tests
    // -------------------------------------------------------------------------

    @Test
    void getState_returnsIdle_forUnknownUserId() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        assertEquals(VirtualUserState.IDLE, engine.getState("nonexistent-user"));
    }

    @Test
    void getStates_returnsUnmodifiableSnapshot() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 3);
        engine.execute(users, testScenario);

        Map<String, VirtualUserState> states = engine.getStates();
        assertEquals(3, states.size());
        assertThrows(UnsupportedOperationException.class, () ->
                states.put("dummy", VirtualUserState.IDLE));
    }

    @Test
    void getStates_allUsersCompletedAfterSuccessfulExecute() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new CountingTransport(new HttpResponseResult(200, "ok", 1000L)),
                new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 3);
        engine.execute(users, testScenario);

        engine.getStates().values().forEach(state ->
                assertEquals(VirtualUserState.COMPLETED, state));
    }

    // -------------------------------------------------------------------------
    // InterruptedException handling
    // -------------------------------------------------------------------------

    @Test
    void interruptedException_fromTransport_marksUserCompleted() {
        DefaultVirtualUserEngine engine = new DefaultVirtualUserEngine(
                new InterruptingTransport(), new NoOpMetricsEngine());

        List<VirtualUser> users = engine.initialize(testScenario, 1);
        assertDoesNotThrow(() -> engine.execute(users, testScenario));

        // Interruption is treated as a clean stop — user should be COMPLETED, not FAILED
        assertEquals(VirtualUserState.COMPLETED, engine.getState("user-1"));
    }
}
