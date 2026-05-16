# Implementation Plan: LatencyLab Phase 3 — Virtual User Concurrency

## Overview

Rewrite `DefaultVirtualUserEngine` from its Phase 2 stub into a production-grade concurrent execution harness, and create `DefaultLoadScheduler` from scratch. The engine uses Java 21 virtual threads, a `ConcurrentHashMap` User_State_Registry, an `AtomicBoolean` Shutdown_Signal, and an `AtomicReference<CountDownLatch>` Pause_Latch. `DefaultLoadScheduler` supports both ramp-up and constant load profiles via a pluggable activation callback. All tasks must keep `mvn verify` passing at every step.

## Tasks

- [x] 1. Rewrite `DefaultVirtualUserEngine` — constructor and internal fields
  - [x] 1.1 Replace the Phase 2 stub constructor with the Phase 3 constructor signature
    - Change constructor to `public DefaultVirtualUserEngine(HttpTransportLayer transport, MetricsEngine metricsEngine)`
    - Throw `NullPointerException` if either argument is null (check `transport` first, then `metricsEngine`)
    - Add `private final MetricsEngine metricsEngine` field
    - Add `private final AtomicBoolean shutdownSignal = new AtomicBoolean(false)`
    - Add `private final AtomicReference<CountDownLatch> pauseLatch = new AtomicReference<>(new CountDownLatch(0))`
    - Add `private final ConcurrentHashMap<String, VirtualUserState> userStateRegistry = new ConcurrentHashMap<>()`
    - Keep `private final HttpTransportLayer transport` field
    - Keep `private static final Logger log` field
    - Update all existing test classes that construct `DefaultVirtualUserEngine(transport)` to pass a no-op `MetricsEngine` as the second argument so `mvn verify` continues to pass
    - _Requirements: 5.2, 9.1_

- [x] 2. Rewrite `DefaultVirtualUserEngine` — `initialize` method
  - [x] 2.1 Keep the existing `initialize(Scenario scenario, int userCount)` implementation intact
    - Verify it still returns an unmodifiable list of `VirtualUser` records with `state = IDLE`, `userId = "user-" + (i+1)`, `activeScenario = scenario`, `metricsSnapshot = null`
    - Verify it still throws `NullPointerException` for null scenario and `IllegalArgumentException` for `userCount < 1`
    - No code changes needed if the Phase 2 implementation is correct — this task is a verification step
    - _Requirements: 9.1_

- [x] 3. Rewrite `DefaultVirtualUserEngine` — public lifecycle API
  - [x] 3.1 Add `getState(String userId)` method
    - Return `userStateRegistry.getOrDefault(userId, VirtualUserState.IDLE)`
    - _Requirements: 2.7_

  - [x] 3.2 Add `getStates()` method
    - Return `Collections.unmodifiableMap(new HashMap<>(userStateRegistry))`
    - The returned map must throw `UnsupportedOperationException` on mutation
    - _Requirements: 2.8_

  - [x] 3.3 Add `pause()` method
    - Replace the current `pauseLatch` with a new unreleased latch: `pauseLatch.set(new CountDownLatch(1))`
    - _Requirements: 2.4_

  - [x] 3.4 Add `resume()` method
    - Count down the current latch: `pauseLatch.get().countDown()`
    - If the engine is not paused (latch already at 0), this is a no-op — no exception
    - _Requirements: 2.5_

  - [x] 3.5 Add `stop()` method
    - Set `shutdownSignal.set(true)`
    - Release any blocked threads: `pauseLatch.get().countDown()`
    - Idempotent — calling multiple times must not throw
    - _Requirements: 6.1, 6.3, 6.6_

- [x] 4. Rewrite `DefaultVirtualUserEngine` — per-user runnable (`runUser`)
  - [x] 4.1 Rewrite `runUser(VirtualUser user, Scenario scenario)` with full lifecycle logic
    - Register `RUNNING` in `userStateRegistry` before the first step: `userStateRegistry.put(userId, RUNNING)`
    - Log DEBUG: `"Starting user {} with {} steps"` (userId, step count)
    - Iterate `scenario.steps()` in order; for each step:
      1. Call `result = transport.execute(step)` inside a try block
      2. On success: call `metricsEngine.record(result.latencyNanos(), result.statusCode() >= 200 && result.statusCode() <= 299)` — this must happen before the shutdown/pause checks
      3. If `metricsEngine.record` throws any exception: log ERROR with userId, set `FAILED` in registry, return
      4. Check `shutdownSignal.get()`: if true, log DEBUG `"User {} stopped"`, set `COMPLETED`, return
      5. Call `pauseLatch.get().await()` inside a try/catch for `InterruptedException`
      6. If `await()` throws `InterruptedException`: re-interrupt thread, set `COMPLETED`, return
      7. Re-check `shutdownSignal.get()` after latch release: if true, log DEBUG `"User {} stopped after resume"`, set `COMPLETED`, return
      - Catch `InterruptedException` from `transport.execute`: re-interrupt thread, set `COMPLETED`, return
      - Catch any other `Exception` from `transport.execute`: log ERROR with userId and step name, set `FAILED`, return
    - After loop completes: log DEBUG `"User {} completed"`, set `COMPLETED` in registry
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.4, 5.1, 5.3, 5.4, 5.5_

- [ ] 5. Rewrite `DefaultVirtualUserEngine` — `execute` method
  - [x] 5.1 Rewrite `execute(List<VirtualUser> users, Scenario scenario)` with shutdown-signal guard and thread naming
    - `Objects.requireNonNull(users, ...)` and `Objects.requireNonNull(scenario, ...)`
    - If `users.isEmpty()` return immediately
    - If `shutdownSignal.get()` is true, return immediately without launching any threads
    - For each user: `Thread.ofVirtual().name("vuser-" + user.userId()).start(() -> runUser(user, scenario))`; collect all threads in a `List<Thread>`
    - Join all threads in a loop; on `InterruptedException`: re-interrupt calling thread and break
    - After all threads join: if `shutdownSignal.get()` is true, log INFO that all virtual users have stopped
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 6.4, 6.5, 6.7_

- [ ] 6. Checkpoint — compile and existing tests pass
  - Ensure `mvn verify` exits with `BUILD SUCCESS`. All pre-existing tests must pass with the updated constructor. Ask the user if questions arise.

- [ ] 7. Update `DefaultVirtualUserEngineTest` — new Phase 3 test methods
  - [ ] 7.1 Add a no-op `MetricsEngine` inner class (`NoOpMetricsEngine`) to the test class
    - Implements `MetricsEngine`; `record(long, boolean)` is a no-op; `snapshot()` returns null
    - Update all existing test methods to pass `new NoOpMetricsEngine()` as the second constructor argument

  - [ ] 7.2 Add `constructor_nullTransport_throwsNPE`
    - `new DefaultVirtualUserEngine(null, new NoOpMetricsEngine())` → `NullPointerException`
    - _Requirements: 5.2_

  - [ ] 7.3 Add `constructor_nullMetricsEngine_throwsNPE`
    - `new DefaultVirtualUserEngine(transport, null)` → `NullPointerException`
    - _Requirements: 5.2_

  - [ ] 7.4 Add `execute_invokesTransportOncePerStepPerUser` (replace/update existing)
    - Use a `CountingMetricsEngine` inner class (thread-safe `AtomicInteger` counter)
    - 3 users × 2 steps → transport called 6 times, `metricsEngine.record` called 6 times
    - _Requirements: 1.1, 5.1, 9.4a_

  - [ ] 7.5 Add `execute_failingUser_doesNotPreventOthersFromCompleting`
    - 1 user with `FailingTransport`, 2 users with `CountingTransport`
    - After `execute` returns: `getState("user-1")` == `FAILED`, `getState("user-2")` == `COMPLETED`, `getState("user-3")` == `COMPLETED`
    - `execute` must not throw
    - _Requirements: 4.1, 4.2, 4.3, 9.4b_

  - [ ] 7.6 Add `pause_blocksThreadsUntilResume`
    - Use a `BlockingTransport` that blocks on a `CountDownLatch` until released; call `engine.pause()` before releasing the transport latch; verify no further transport calls are made until `engine.resume()` is called
    - _Requirements: 2.4, 2.5, 9.4c_

  - [ ] 7.7 Add `stop_causesThreadsToExitAfterCurrentCall`
    - Use a `CountingTransport` with a multi-step scenario; call `engine.stop()` after the first step completes; verify transport is not called for subsequent steps
    - _Requirements: 6.1, 6.2, 9.4d_

  - [ ] 7.8 Add `metricsRecord_calledOncePerResult_notCalledOnException`
    - Use a `CountingMetricsEngine`; mix one `FailingTransport` user and one `CountingTransport` user with 3 steps each; verify `metricsEngine.record` called exactly 3 times (only for the successful user)
    - _Requirements: 5.1, 5.4, 9.4e_

  - [ ] 7.9 Add `stop_idempotent_noExceptionOnMultipleCalls`
    - Call `engine.stop()` 5 times on the same instance; assert no exception is thrown
    - _Requirements: 6.6_

  - [ ] 7.10 Add `execute_afterStop_returnsImmediately`
    - Call `engine.stop()` before `execute`; verify `execute` returns without launching threads (transport call count remains 0)
    - _Requirements: 6.5, 6.7_

  - [ ] 7.11 Add `interruptedException_fromTransport_marksUserCompleted`
    - Use a transport that throws `InterruptedException` wrapped in a `RuntimeException` (or directly if the interface allows checked exceptions); verify user state is `COMPLETED` not `FAILED`
    - _Requirements: 4.4_

  - [ ] 7.12 Add `getState_returnsIdle_forUnknownUserId`
    - Call `engine.getState("nonexistent-user")` before any `execute`; assert result is `VirtualUserState.IDLE`
    - _Requirements: 2.7_

  - [ ] 7.13 Add `getStates_returnsUnmodifiableSnapshot`
    - After `execute` with 3 users, call `getStates()`; assert size == 3; assert `map.put(...)` throws `UnsupportedOperationException`
    - _Requirements: 2.8_

- [ ] 8. Update `DefaultVirtualUserEngineComplianceTest` — extend with Phase 3 checks
  - [ ] 8.1 Add reflection test for `pause()`, `resume()`, `stop()`, `getState()`, `getStates()` method presence
    - Use `DefaultVirtualUserEngine.class.getDeclaredMethod(...)` to assert each method exists with the correct signature
    - _Requirements: 9.2_

- [ ] 9. Checkpoint — engine tests pass
  - Run `mvn verify` and confirm `BUILD SUCCESS` with all engine tests passing. Ask the user if questions arise.

- [ ] 10. Update `DefaultVirtualUserEnginePropertyTest` — Phase 3 properties
  - [ ] 10.1 Add a `CountingMetricsEngine` inner class to the property test class
    - Thread-safe `AtomicInteger recordCount`; `record(long, boolean)` increments counter; `snapshot()` returns null

  - [ ]* 10.2 Write property test for metrics record count (Property 1)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 1: MetricsEngine.record is called exactly once per returned HttpResponseResult`
    - `@Property(tries = 100)`: generate `userCount` in [1, 20] and `stepCount` in [1, 10]; all transport calls return `HttpResponseResult(200, "ok", 1000L)`; assert `metricsEngine.recordCount == userCount * stepCount` after `execute` returns
    - **Property 1: MetricsEngine.record is called exactly once per returned HttpResponseResult**
    - **Validates: Requirements 5.1, 5.2**

  - [ ]* 10.3 Write property test for metrics success flag (Property 2)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 2: MetricsEngine.record success flag matches HTTP status code range`
    - `@Property(tries = 100)`: generate `statusCode` from two ranges — [200, 299] and [0, 199] ∪ [300, 599]; use a `CapturingMetricsEngine` that records `(latencyNanos, success)` pairs in a `CopyOnWriteArrayList`; assert `success == true` iff `statusCode in [200, 299]`
    - **Property 2: MetricsEngine.record success flag matches HTTP status code range**
    - **Validates: Requirements 5.1**

  - [ ]* 10.4 Write property test for User_State_Registry final states (Property 3)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 3: User_State_Registry contains exactly one entry per virtual user after execute completes`
    - `@Property(tries = 100)`: generate `userCount` in [1, 20] and `stepCount` in [1, 5]; after `execute` returns, assert `getStates().size() == userCount`; assert every value is `COMPLETED` or `FAILED` (never `IDLE` or `RUNNING`)
    - **Property 3: User_State_Registry contains exactly one entry per virtual user after execute completes**
    - **Validates: Requirements 2.2, 2.3, 2.8**

  - [ ]* 10.5 Write property test for per-user exception isolation (Property 4)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 4: Per-user exception isolation — failing users do not reduce the metrics record count for other users`
    - `@Property(tries = 100)`: generate `userCount` in [2, 20] and `stepCount` in [1, 5]; designate exactly one user's transport to always throw `RuntimeException`; assert `metricsEngine.recordCount == (userCount - 1) * stepCount` and `execute` does not throw
    - **Property 4: Per-user exception isolation — failing users do not reduce the metrics record count for other users**
    - **Validates: Requirements 4.1, 4.2, 4.3, 5.4**

  - [ ]* 10.6 Write property test for stop() idempotency (Property 7)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 7: stop() is idempotent — calling it N times never throws`
    - `@Property(tries = 100)`: generate `stopCount` in [2, 10]; call `engine.stop()` `stopCount` times; assert no exception is thrown on any call
    - **Property 7: stop() is idempotent — calling it N times never throws**
    - **Validates: Requirement 6.6**

  - [ ]* 10.7 Write property test for getStates() snapshot size (Property 8)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 8: getStates() snapshot size equals the number of users passed to execute`
    - `@Property(tries = 100)`: generate `userCount` in [1, 20]; after `execute` returns, assert `getStates().size() == userCount`; assert key set equals the set of `userId` values from the input list; assert `getStates().put(...)` throws `UnsupportedOperationException`
    - **Property 8: getStates() snapshot size equals the number of users passed to execute**
    - **Validates: Requirements 2.8**

- [ ] 11. Checkpoint — engine property tests pass
  - Run `mvn verify` and confirm `BUILD SUCCESS`. Ask the user if questions arise.

- [x] 12. Create `DefaultLoadScheduler`
  - [x] 12.1 Create `DefaultLoadScheduler.java` in `src/main/java/com/latencylab/scheduler/`
    - Declare `public class DefaultLoadScheduler implements LoadScheduler`
    - Add `private static final Logger log = LoggerFactory.getLogger(DefaultLoadScheduler.class)`
    - Add `private final Runnable activationCallback`
    - Add `private final AtomicReference<SchedulerState> state = new AtomicReference<>(SchedulerState.IDLE)`
    - Constructor: `public DefaultLoadScheduler(Runnable activationCallback)` — store the callback; throw `NullPointerException` if null
    - _Requirements: 7.1, 9.3_

  - [x] 12.2 Implement `start(Scenario scenario)` method
    - `Objects.requireNonNull(scenario, "scenario must not be null")` — NPE before any state check
    - If `state.get() == SchedulerState.RUNNING` throw `IllegalStateException`
    - `state.set(SchedulerState.RUNNING)` — transition before first activation
    - Compute `long delayMs = scenario.rampUpSeconds() == 0 ? 0L : (scenario.rampUpSeconds() * 1000L) / scenario.userCount()`
    - Loop `i` from 0 to `scenario.userCount() - 1`:
      - If `state.get() != SchedulerState.RUNNING` break
      - Call `activationCallback.run()`
      - If `delayMs > 0` and `i < scenario.userCount() - 1`: call `Thread.sleep(delayMs)` inside try/catch for `InterruptedException` (re-interrupt on catch and break)
    - _Requirements: 7.2, 7.3, 7.4, 7.8, 7.9, 8.1_

  - [x] 12.3 Implement `pause()`, `resume()`, `stop()`, and `getState()` methods
    - `pause()`: `state.set(SchedulerState.PAUSED)` — no-op if not RUNNING (no exception)
    - `resume()`: `state.set(SchedulerState.RUNNING)` — no-op if not PAUSED (no exception)
    - `stop()`: `state.set(SchedulerState.STOPPED)` — idempotent, no exception
    - `getState()`: return `state.get()`
    - _Requirements: 7.5, 7.6, 7.7, 8.2, 8.3_

- [ ] 13. Checkpoint — compile scheduler
  - Run `mvn compile` and confirm no errors. Ask the user if questions arise.

- [ ] 14. Create `DefaultLoadSchedulerTest`
  - [ ] 14.1 Create `DefaultLoadSchedulerTest.java` in `src/test/java/com/latencylab/scheduler/`
    - Use `AtomicInteger activationCount` as the activation callback counter in each test
    - `getState_returnsIdle_beforeStart`: assert `getState() == IDLE` on a fresh instance
    - `start_transitionsToRunning`: call `start(scenario)`; assert `getState() == RUNNING`
    - `pause_transitionsToPaused`: call `start`, then `pause()`; assert `getState() == PAUSED`
    - `resume_transitionsBackToRunning`: call `start`, `pause()`, `resume()`; assert `getState() == RUNNING`
    - `stop_transitionsToStopped`: call `start`, then `stop()`; assert `getState() == STOPPED`
    - `rampUp_delayFormula_correctForKnownValues`: use `rampUpSeconds = 60`, `userCount = 100`; verify computed delay is 600 ms (test the formula, not the actual sleep — use a scenario with 1 user to avoid sleep in tests)
    - `constantLoad_activatesAllUsersImmediately`: `rampUpSeconds = 0`, `userCount = 5`; assert `activationCount == 5` after `start` returns
    - `rampUp_activatesAllUsers`: `rampUpSeconds = 0` (to avoid sleep), `userCount = 10`; assert `activationCount == 10`
    - `start_nullScenario_throwsNPE`: `start(null)` → `NullPointerException`
    - `start_whenAlreadyRunning_throwsISE`: call `start` twice (use `rampUpSeconds = 0`, `userCount = 1` for first call to complete quickly); assert second call throws `IllegalStateException`
    - `stop_idempotent_noExceptionOnMultipleCalls`: call `stop()` 3 times; assert no exception
    - _Requirements: 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 8.1, 8.2, 8.3, 9.4a, 9.4b, 9.5_

- [ ] 15. Create `DefaultLoadSchedulerComplianceTest`
  - [ ] 15.1 Create `DefaultLoadSchedulerComplianceTest.java` in `src/test/java/com/latencylab/scheduler/`
    - Assert `LoadScheduler.class.isAssignableFrom(DefaultLoadScheduler.class)` returns `true`
    - _Requirements: 7.1, 9.3_

- [ ] 16. Checkpoint — scheduler tests pass
  - Run `mvn verify` and confirm `BUILD SUCCESS`. Ask the user if questions arise.

- [ ] 17. Create `DefaultLoadSchedulerPropertyTest`
  - [ ] 17.1 Create `DefaultLoadSchedulerPropertyTest.java` in `src/test/java/com/latencylab/scheduler/`

  - [ ]* 17.2 Write property test for ramp-up delay arithmetic (Property 5)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 5: Ramp-up inter-activation delay is non-negative for all valid rampUpSeconds and userCount values`
    - `@Property(tries = 100)`: generate `rampUpSeconds` in [1, 3600] and `userCount` in [1, 100000]; compute `long delay = (rampUpSeconds * 1000L) / userCount`; assert `delay >= 0` and no arithmetic exception is thrown
    - **Property 5: Ramp-up inter-activation delay is non-negative for all valid rampUpSeconds and userCount values**
    - **Validates: Requirement 7.2**

  - [ ]* 17.3 Write property test for constant load profile (Property 6)
    - `// Feature: latencylab-phase3-virtual-user-concurrency, Property 6: Constant load profile activates all users with no inter-activation delay`
    - `@Property(tries = 100)`: generate `userCount` in [1, 1000]; create a `Scenario` with `rampUpSeconds = 0` and `userCount`; use `AtomicInteger` callback counter; call `start(scenario)`; assert `activationCount == userCount` and `getState() == RUNNING`
    - **Property 6: Constant load profile activates all users with no inter-activation delay**
    - **Validates: Requirements 7.3, 8.1, 8.2**

- [ ] 18. Final Checkpoint — full `mvn verify`
  - Run `mvn verify` and confirm `BUILD SUCCESS` with all tests discovered and passing. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- `DefaultVirtualUserEngine` constructor signature changes in task 1.1 — all existing test classes must be updated in the same task to keep `mvn verify` green
- `MetricsEngine.record` is called before the shutdown/pause check — this ordering is mandated by the design
- `pauseLatch` is initialized with `new CountDownLatch(0)` (already released) so virtual threads never block unless `pause()` is explicitly called
- `DefaultLoadScheduler.start()` validates the null scenario argument before checking state — NPE always takes precedence over ISE
- Property tests use `@Property(tries = 100)` minimum; each includes a comment with feature name and property number
- No new `pom.xml` dependencies are needed — jqwik 1.8.4 and JUnit 5.10.2 are already present

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "3.2", "3.3", "3.4", "3.5"] },
    { "id": 2, "tasks": ["4.1"] },
    { "id": 3, "tasks": ["5.1"] },
    { "id": 4, "tasks": ["7.1", "12.1"] },
    { "id": 5, "tasks": ["7.2", "7.3", "7.4", "7.5", "7.6", "7.7", "7.8", "7.9", "7.10", "7.11", "7.12", "7.13", "8.1", "12.2"] },
    { "id": 6, "tasks": ["10.1", "12.3", "14.1", "15.1"] },
    { "id": 7, "tasks": ["10.2", "10.3", "10.4", "10.5", "10.6", "10.7", "17.1"] },
    { "id": 8, "tasks": ["17.2", "17.3"] }
  ]
}
```
