package com.latencylab.scheduler;

import com.latencylab.model.Scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link LoadScheduler} supporting both ramp-up and
 * constant load profiles.
 *
 * <p>The scheduler activates virtual users by invoking a pluggable
 * {@link Runnable} callback once per user. In production wiring this callback
 * starts a virtual user thread; in tests it can be a simple counter increment.
 *
 * <p>Ramp-up profile: inter-activation delay =
 * {@code (rampUpSeconds * 1000L) / userCount} ms (integer division, minimum 0).
 * Constant load profile: {@code rampUpSeconds == 0} → all users activated
 * immediately with no sleep between activations.
 */
public class DefaultLoadScheduler implements LoadScheduler {

    private static final Logger log = LoggerFactory.getLogger(DefaultLoadScheduler.class);

    private final Runnable activationCallback;
    private final AtomicReference<SchedulerState> state =
            new AtomicReference<>(SchedulerState.IDLE);

    /**
     * Constructs a DefaultLoadScheduler.
     *
     * @param activationCallback invoked once per user activation; must not be null
     * @throws NullPointerException if activationCallback is null
     */
    public DefaultLoadScheduler(Runnable activationCallback) {
        Objects.requireNonNull(activationCallback, "activationCallback must not be null");
        this.activationCallback = activationCallback;
    }

    /**
     * Starts the scheduler, activating virtual users according to the load profile
     * defined by the scenario.
     *
     * <p>Parameter validation (null check) occurs before state validation, so a
     * null scenario always throws {@link NullPointerException} regardless of the
     * current state.
     *
     * @param scenario the scenario describing the load profile; must not be null
     * @throws NullPointerException  if scenario is null
     * @throws IllegalStateException if the scheduler is already in RUNNING state
     */
    @Override
    public void start(Scenario scenario) {
        // Parameter validation before state check (NPE takes precedence over ISE)
        Objects.requireNonNull(scenario, "scenario must not be null");

        if (state.get() == SchedulerState.RUNNING) {
            throw new IllegalStateException(
                    "Scheduler is already running; call stop() before starting again");
        }

        // Transition to RUNNING before activating any users
        state.set(SchedulerState.RUNNING);
        log.info("LoadScheduler starting: userCount={}, rampUpSeconds={}",
                scenario.userCount(), scenario.rampUpSeconds());

        long delayMs = scenario.rampUpSeconds() == 0
                ? 0L
                : (scenario.rampUpSeconds() * 1000L) / scenario.userCount();

        for (int i = 0; i < scenario.userCount(); i++) {
            // Respect pause/stop signals between activations
            SchedulerState current = state.get();
            if (current == SchedulerState.STOPPED) {
                log.debug("LoadScheduler stopped during ramp-up after {} activations", i);
                break;
            }
            if (current == SchedulerState.PAUSED) {
                // Spin-wait until resumed or stopped
                while (state.get() == SchedulerState.PAUSED) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.debug("LoadScheduler interrupted during pause");
                        return;
                    }
                }
                // Re-check after unpausing
                if (state.get() == SchedulerState.STOPPED) {
                    break;
                }
            }

            activationCallback.run();
            log.debug("Activated user {}/{}", i + 1, scenario.userCount());

            // Sleep between activations (skip after the last one)
            if (delayMs > 0 && i < scenario.userCount() - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("LoadScheduler interrupted during ramp-up sleep");
                    return;
                }
            }
        }

        log.info("LoadScheduler finished activating all users");
    }

    /**
     * Pauses the scheduler. If the scheduler is not currently RUNNING, this is a
     * no-op (no exception thrown).
     */
    @Override
    public void pause() {
        state.compareAndSet(SchedulerState.RUNNING, SchedulerState.PAUSED);
        log.debug("LoadScheduler paused");
    }

    /**
     * Stops the scheduler and cancels any pending activations. Idempotent — calling
     * multiple times never throws.
     */
    @Override
    public void stop() {
        state.set(SchedulerState.STOPPED);
        log.debug("LoadScheduler stopped");
    }

    /**
     * Returns the current scheduler state.
     */
    @Override
    public SchedulerState getState() {
        return state.get();
    }

    /**
     * Resumes a paused scheduler. If the scheduler is not currently PAUSED, this is
     * a no-op (no exception thrown).
     *
     * <p>Note: {@code resume()} is not part of the {@link LoadScheduler} interface
     * but is provided as a convenience method on this concrete class.
     */
    public void resume() {
        state.compareAndSet(SchedulerState.PAUSED, SchedulerState.RUNNING);
        log.debug("LoadScheduler resumed");
    }
}
