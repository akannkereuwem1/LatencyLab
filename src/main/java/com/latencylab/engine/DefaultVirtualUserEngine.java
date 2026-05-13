package com.latencylab.engine;

import com.latencylab.model.Scenario;
import com.latencylab.model.VirtualUser;
import com.latencylab.model.VirtualUserState;
import com.latencylab.transport.HttpTransportLayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * Default implementation of VirtualUserEngine using Java 21 virtual threads.
 */
public class DefaultVirtualUserEngine implements VirtualUserEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultVirtualUserEngine.class);
    private final HttpTransportLayer transport;

    public DefaultVirtualUserEngine(HttpTransportLayer transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        this.transport = transport;
    }

    @Override
    public List<VirtualUser> initialize(Scenario scenario, int userCount) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        if (userCount < 1) {
            throw new IllegalArgumentException("userCount must be at least 1");
        }

        List<VirtualUser> users = new ArrayList<>(userCount);
        for (int i = 0; i < userCount; i++) {
            String userId = "user-" + (i + 1);
            VirtualUser user = new VirtualUser(
                    userId,
                    VirtualUserState.IDLE,
                    scenario,
                    null
            );
            users.add(user);
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

        List<Thread> threads = new ArrayList<>(users.size());
        ThreadFactory virtualThreadFactory = Thread.ofVirtual()
                .name("vuser-", 0)
                .factory();

        for (VirtualUser user : users) {
            Thread thread = virtualThreadFactory.newThread(() -> runUser(user, scenario));
            thread.start();
            threads.add(thread);
        }

        // Join all threads
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Runs a single virtual user through all steps in a scenario.
     * 
     * @param user the virtual user to run
     * @param scenario the scenario to execute
     */
    private void runUser(VirtualUser user, Scenario scenario) {
        // Create new VirtualUser record with RUNNING state
        VirtualUser runningUser = new VirtualUser(
                user.userId(),
                VirtualUserState.RUNNING,
                user.activeScenario(),
                user.metricsSnapshot()
        );
        
        log.debug("Starting virtual user '{}' with {} steps", 
                runningUser.userId(), scenario.steps().size());

        // Execute each step sequentially
        for (var step : scenario.steps()) {
            try {
                transport.execute(step);
                // Continue to next step on success
            } catch (Exception e) {
                // On any exception, mark user as FAILED and break
                log.error("Virtual user '{}' failed at step '{}': {}", 
                        user.userId(), step.name(), e.getMessage(), e);
                
                VirtualUser failedUser = new VirtualUser(
                        user.userId(),
                        VirtualUserState.FAILED,
                        user.activeScenario(),
                        user.metricsSnapshot()
                );
                // In a full implementation, we might update some shared state here
                // For now, we just break as the method doesn't return the final state
                break;
            }
        }

        // If we completed all steps, mark as COMPLETED
        // Note: We don't have a way to return the final state from this void method
        // In a more complete implementation, we might collect results or use callbacks
        log.debug("Completed virtual user '{}'", user.userId());
    }
}