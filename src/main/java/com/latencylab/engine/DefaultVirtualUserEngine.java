package com.latencylab.engine;

import com.latencylab.metrics.MetricsEngine;
import com.latencylab.model.Scenario;
import com.latencylab.model.VirtualUser;
import com.latencylab.model.VirtualUserState;
import com.latencylab.transport.HttpResponseResult;
import com.latencylab.transport.HttpTransportLayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of VirtualUserEngine using Java 21 virtual threads.
 *
 * <p>Each virtual user runs in its own virtual thread. The engine supports
 * pause/resume via a CountDownLatch and graceful shutdown via an AtomicBoolean
 * shutdown signal. Per-user state is tracked in a ConcurrentHashMap.
 */
public class DefaultVirtualUserEngine implements VirtualUserEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultVirtualUserEngine.class);

    private final HttpTransportLayer transport;
    private final MetricsEngine metricsEngine;
    private final AtomicBoolean shutdownSignal = new AtomicBoolean(false);
    private final AtomicReference<CountDownLatch> pauseLatch = new AtomicReference<>(new CountDownLatch(0));
    private final ConcurrentHashMap<String, VirtualUserState> userStateRegistry = new ConcurrentHashMap<>();

    /**
     * Constructs a DefaultVirtualUserEngine.
     *
     * @param transport     the HTTP transport layer used to execute request steps
     * @param metricsEngine the metrics engine used to record per-request results
     * @throws NullPointerException if transport or metricsEngine is null
     */
    public DefaultVirtualUserEngine(HttpTransportLayer transport, MetricsEngine metricsEngine) {
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(metricsEngine, "metricsEngine must not be null");
        this.transport = transport;
        this.metricsEngine = metricsEngine;
    }

    // -------------------------------------------------------------------------
    // VirtualUserEngine interface
    // -------------------------------------------------------------------------

    @Override
    public List<VirtualUser> initialize(Scenario scenario, int userCount) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        if (userCount < 1) {
            throw new IllegalArgumentException("userCount must be at least 1");
        }

        List<VirtualUser> users = new ArrayList<>(userCount);
        for (int i = 0; i < userCount; i++) {
            String userId = "user-" + (i + 1);
            users.add(new VirtualUser(userId, VirtualUserState.IDLE, scenario, null));
        }
        return Collections.unmodifiableList(users);
    }

    @Override
    public void execute(List<VirtualUser> users, Scenario scenario) {
        Objects.requireNonNull(users, "users must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");

        if (users.isEmpty()) {
            return;
        }

        // Return immediately if shutdown signal is already set
        if (shutdownSignal.get()) {
            return;
        }

        List<Thread> threads = new ArrayList<>(users.size());
        for (VirtualUser user : users) {
            Thread thread = Thread.ofVirtual()
                    .name("vuser-" + user.userId())
                    .start(() -> runUser(user, scenario));
            threads.add(thread);
        }

        // Block until all virtual threads have terminated
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (shutdownSignal.get()) {
            log.info("All virtual users have stopped");
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle API (not part of VirtualUserEngine interface)
    // -------------------------------------------------------------------------

    /**
     * Returns the current state of the virtual user with the given userId.
     * Returns {@link VirtualUserState#IDLE} if the userId is not found.
     */
    public VirtualUserState getState(String userId) {
        return userStateRegistry.getOrDefault(userId, VirtualUserState.IDLE);
    }

    /**
     * Returns an unmodifiable snapshot of all user states.
     */
    public Map<String, VirtualUserState> getStates() {
        return Collections.unmodifiableMap(new HashMap<>(userStateRegistry));
    }

    /**
     * Pauses all virtual user threads at the next inter-step boundary.
     * Replaces the current latch with a new unreleased one.
     */
    public void pause() {
        pauseLatch.set(new CountDownLatch(1));
    }

    /**
     * Resumes all paused virtual user threads. No-op if not currently paused.
     */
    public void resume() {
        pauseLatch.get().countDown();
    }

    /**
     * Signals all virtual user threads to stop after their current in-flight call.
     * Also releases any threads blocked on the pause latch. Idempotent.
     */
    public void stop() {
        shutdownSignal.set(true);
        // Release any threads blocked on the pause latch so they can observe the signal
        pauseLatch.get().countDown();
    }

    // -------------------------------------------------------------------------
    // Per-user execution
    // -------------------------------------------------------------------------

    /**
     * Runs a single virtual user through all steps in the scenario.
     * Called from within a virtual thread.
     */
    private void runUser(VirtualUser user, Scenario scenario) {
        String userId = user.userId();
        userStateRegistry.put(userId, VirtualUserState.RUNNING);
        log.debug("Starting user {} with {} steps", userId, scenario.steps().size());

        for (var step : scenario.steps()) {
            HttpResponseResult result;
            try {
                result = transport.execute(step);
            } catch (Exception e) {
                // If the exception wraps or is caused by an interruption, treat as clean stop
                if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    log.debug("User {} stopped due to interrupt", userId);
                    userStateRegistry.put(userId, VirtualUserState.COMPLETED);
                    return;
                }
                log.error("Virtual user '{}' failed at step '{}': {}", userId, step.name(), e.getMessage(), e);
                userStateRegistry.put(userId, VirtualUserState.FAILED);
                return;
            }

            // Record metrics immediately after transport returns, before any signal checks
            try {
                boolean success = result.statusCode() >= 200 && result.statusCode() <= 299;
                metricsEngine.record(result.latencyNanos(), success);
            } catch (Exception e) {
                log.error("MetricsEngine.record failed for user '{}': {}", userId, e.getMessage(), e);
                userStateRegistry.put(userId, VirtualUserState.FAILED);
                return;
            }

            // Check shutdown signal before proceeding to next step
            if (shutdownSignal.get()) {
                log.debug("User {} stopped", userId);
                userStateRegistry.put(userId, VirtualUserState.COMPLETED);
                return;
            }

            // Check pause latch
            try {
                pauseLatch.get().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("User {} stopped after resume", userId);
                userStateRegistry.put(userId, VirtualUserState.COMPLETED);
                return;
            }

            // Re-check shutdown signal after latch release
            if (shutdownSignal.get()) {
                log.debug("User {} stopped after resume", userId);
                userStateRegistry.put(userId, VirtualUserState.COMPLETED);
                return;
            }
        }

        log.debug("User {} completed", userId);
        userStateRegistry.put(userId, VirtualUserState.COMPLETED);
    }
}
