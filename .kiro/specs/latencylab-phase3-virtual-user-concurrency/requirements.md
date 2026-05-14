# Requirements Document

## Introduction

Phase 3 of LatencyLab implements Virtual User Concurrency — the full lifecycle engine that turns the Phase 2 stub `DefaultVirtualUserEngine` into a production-grade concurrent execution harness. This phase wires together virtual user spawning, per-user scenario execution, lifecycle management (start, pause, resume, stop), thread-safe metrics collection hooks, graceful shutdown, per-user error isolation, and load scheduler integration (ramp-up and constant load profiles). Java 21 virtual threads (Project Loom) remain the concurrency primitive. The `MetricsEngine` interface is called per completed request but its aggregation logic is not implemented in this phase — that arrives in Phase 4.

## Glossary

- **DefaultVirtualUserEngine**: The concrete class in `com.latencylab.engine` that implements `VirtualUserEngine`, driving virtual users concurrently through scenario steps using Java virtual threads.
- **VirtualUserEngine**: The Phase 1 interface in `com.latencylab.engine` with methods `initialize(Scenario scenario, int userCount): List<VirtualUser>` and `execute(List<VirtualUser> users, Scenario scenario): void`.
- **VirtualUser**: The Phase 1 record in `com.latencylab.model` representing a simulated user, with `userId`, `state` (`VirtualUserState`), `activeScenario`, and `metricsSnapshot`.
- **VirtualUserState**: The Phase 1 enum in `com.latencylab.model` with values `IDLE`, `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`.
- **Virtual_Thread**: A Java 21 Project Loom lightweight thread created via `Thread.ofVirtual().start(...)`.
- **Scenario**: The Phase 1 record in `com.latencylab.model` representing an ordered list of `RequestStep` objects plus test-level configuration (`testName`, `rampUpSeconds`, `durationSeconds`, `userCount`).
- **RequestStep**: The Phase 1 record in `com.latencylab.model` representing a single HTTP operation, containing `method`, `endpoint`, `body`, `headers`, and `timeoutMillis`.
- **HttpTransportLayer**: The Phase 1 interface in `com.latencylab.transport` with a single method `execute(RequestStep step): HttpResponseResult`.
- **HttpResponseResult**: The Phase 1 record in `com.latencylab.transport` containing `statusCode` (int), `responseBody` (String, nullable), and `latencyNanos` (long).
- **MetricsEngine**: The Phase 1 interface in `com.latencylab.metrics` with methods `record(long latencyNanos, boolean success): void` and `snapshot(): MetricsSnapshot`.
- **LoadScheduler**: The Phase 1 interface in `com.latencylab.scheduler` with methods `start(Scenario scenario): void`, `pause(): void`, `stop(): void`, and `getState(): SchedulerState`.
- **SchedulerState**: The Phase 1 enum in `com.latencylab.scheduler` with values `IDLE`, `RUNNING`, `PAUSED`, `STOPPED`.
- **DefaultLoadScheduler**: The new concrete class in `com.latencylab.scheduler` that implements `LoadScheduler`, coordinating ramp-up and constant load profiles by controlling the rate at which virtual users are activated.
- **Ramp_Up_Profile**: A load profile in which virtual users are activated gradually over `Scenario.rampUpSeconds` seconds, distributing user starts evenly across the ramp-up window.
- **Constant_Load_Profile**: A load profile in which all virtual users are activated immediately at test start (i.e., `Scenario.rampUpSeconds == 0`).
- **Pause_Latch**: A `java.util.concurrent.CountDownLatch` or equivalent synchronization primitive used to block virtual user threads when the engine is paused, releasing them when the engine is resumed.
- **Shutdown_Signal**: A `volatile boolean` or `AtomicBoolean` flag checked by each virtual user thread between steps to determine whether execution should terminate early.
- **User_State_Registry**: A `ConcurrentHashMap<String, VirtualUserState>` held by `DefaultVirtualUserEngine` that maps each `userId` to its current `VirtualUserState`, providing thread-safe cross-thread state visibility.
- **Step_Delay**: An optional inter-step pause duration (in milliseconds) defined per `RequestStep`, used to simulate realistic user pacing between requests.

---

## Requirements

### Requirement 1: Virtual User Spawning with Java Virtual Threads

**User Story:** As a developer, I want the engine to spawn one Java virtual thread per virtual user, so that thousands of users can run concurrently without exhausting OS thread resources.

#### Acceptance Criteria

1. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called, THE `DefaultVirtualUserEngine` SHALL launch exactly one Virtual_Thread per element in the `users` list, such that the total number of Virtual_Threads launched equals `users.size()`.
2. WHEN launching a Virtual_Thread for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL name the thread `"vuser-" + user.userId()` so that `Thread.currentThread().getName()` returns that value from within the thread's runnable.
3. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called, THE `DefaultVirtualUserEngine` SHALL block the calling thread until all Virtual_Threads have terminated — either by completing all steps, by receiving a Shutdown_Signal, or by encountering an unhandled exception — and SHALL NOT return before all threads have reached a terminal state.
4. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called with an empty `users` list, THE `DefaultVirtualUserEngine` SHALL return immediately without launching any threads, and `getStates()` SHALL return an empty map.
5. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called with a null `users` list, THE `DefaultVirtualUserEngine` SHALL throw a `NullPointerException` before launching any threads; WHEN called with a non-null `users` list and a null `Scenario`, THE `DefaultVirtualUserEngine` SHALL throw a `NullPointerException` before launching any threads.
6. WHEN the calling thread is interrupted while blocked waiting for Virtual_Threads to complete, THE `DefaultVirtualUserEngine` SHALL catch the `InterruptedException`, re-interrupt the calling thread via `Thread.currentThread().interrupt()`, and return from `execute` without propagating the exception.

---

### Requirement 2: Virtual User Lifecycle Management

**User Story:** As a developer, I want each virtual user to transition through a well-defined lifecycle (IDLE → RUNNING → PAUSED → RUNNING → COMPLETED/FAILED), so that the engine can be paused, resumed, and stopped cleanly at any point during a test run.

#### Acceptance Criteria

1. WHEN a Virtual_Thread begins executing steps for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL record `VirtualUserState.RUNNING` for that `userId` in the User_State_Registry before invoking `HttpTransportLayer.execute` for the first step.
2. WHEN all steps in the scenario complete without error for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL record `VirtualUserState.COMPLETED` for that `userId` in the User_State_Registry as the final state transition before the thread exits.
3. WHEN `HttpTransportLayer.execute(step)` throws any exception other than `InterruptedException` for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL record `VirtualUserState.FAILED` for that `userId` in the User_State_Registry as the final state transition before the thread exits.
4. WHEN `pause()` is called on `DefaultVirtualUserEngine`, THE `DefaultVirtualUserEngine` SHALL replace the Pause_Latch with a new unreleased latch and record `VirtualUserState.PAUSED` for each Virtual_Thread that blocks on the latch at the next inter-step boundary, preventing further `HttpTransportLayer.execute` calls until `resume()` is called.
5. WHEN `resume()` is called on `DefaultVirtualUserEngine` while the engine is paused, THE `DefaultVirtualUserEngine` SHALL release all Virtual_Threads blocked on the Pause_Latch and record `VirtualUserState.RUNNING` for each unblocked user; IF `resume()` is called when the engine is not paused, THE `DefaultVirtualUserEngine` SHALL complete without throwing an exception and without changing any user state.
6. WHEN `stop()` is called on `DefaultVirtualUserEngine`, THE `DefaultVirtualUserEngine` SHALL set the Shutdown_Signal, causing each Virtual_Thread to exit after completing its current in-flight `HttpTransportLayer.execute` call without starting any further steps; each Virtual_Thread SHALL be permitted to exit independently even if the Shutdown_Signal write is not yet visible to all threads simultaneously.
7. THE `DefaultVirtualUserEngine` SHALL expose a `getState(userId)` method that returns the current `VirtualUserState` for a given `userId` from the User_State_Registry; IF the `userId` is not found in the registry, THE `DefaultVirtualUserEngine` SHALL return `VirtualUserState.IDLE` regardless of any other cached or inferred state.
8. THE `DefaultVirtualUserEngine` SHALL expose a `getStates()` method that returns an unmodifiable snapshot of the entire User_State_Registry as a `Map<String, VirtualUserState>`, such that mutations to the returned map throw `UnsupportedOperationException`.

---

### Requirement 3: Per-User Scenario Execution

**User Story:** As a developer, I want each virtual user to execute all steps of its assigned scenario sequentially, so that the engine faithfully simulates a real user's request flow.

#### Acceptance Criteria

1. WHEN a Virtual_Thread executes steps for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL iterate through `scenario.steps()` in list order, executing each `RequestStep` by calling `HttpTransportLayer.execute(step)`, such that step at index `i` is always executed before step at index `i+1`.
2. WHEN `HttpTransportLayer.execute(step)` returns an `HttpResponseResult` for a step, THE `DefaultVirtualUserEngine` SHALL check the Shutdown_Signal first; IF the Shutdown_Signal is set, THE `DefaultVirtualUserEngine` SHALL exit the step loop for that user without executing further steps; IF the Shutdown_Signal is not set, THE `DefaultVirtualUserEngine` SHALL then check the Pause_Latch.
3. WHEN the Pause_Latch is active after the Shutdown_Signal check, THE `DefaultVirtualUserEngine` SHALL block the Virtual_Thread on the Pause_Latch until it is released; WHEN the latch is released, THE `DefaultVirtualUserEngine` SHALL re-check the Shutdown_Signal before proceeding to the next step, and IF the Shutdown_Signal is now set, SHALL exit the step loop without executing further steps.
4. WHEN a Virtual_Thread begins executing steps, THE `DefaultVirtualUserEngine` SHALL log at DEBUG level a message containing the `userId` and the total number of steps in the scenario.
5. WHEN a Virtual_Thread completes all steps without error — including scenarios with only a single step — THE `DefaultVirtualUserEngine` SHALL log at DEBUG level a message containing the `userId` and the word "completed".
6. WHEN a Virtual_Thread exits early due to the Shutdown_Signal, THE `DefaultVirtualUserEngine` SHALL log at DEBUG level a message containing the `userId` and the word "stopped".

---

### Requirement 4: Per-User Error Isolation

**User Story:** As a developer, I want a failing virtual user to not affect other virtual users, so that one bad request or network error does not abort the entire load test.

#### Acceptance Criteria

1. IF `HttpTransportLayer.execute(step)` throws any `Exception` other than `InterruptedException` for a given `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL catch the exception within that user's Virtual_Thread, log it at ERROR level including the `userId` and `step.name()`, record `VirtualUserState.FAILED` for that `userId` in the User_State_Registry, and terminate that user's step loop without propagating the exception to any other Virtual_Thread.
2. WHEN one `VirtualUser`'s Virtual_Thread terminates due to an exception, all other Virtual_Threads SHALL continue executing their remaining steps and each SHALL reach `VirtualUserState.COMPLETED` (or `VirtualUserState.FAILED` only if they independently encounter their own exception), unaffected by the failing user's termination.
3. WHEN `execute(List<VirtualUser> users, Scenario scenario)` returns after all threads complete, THE `DefaultVirtualUserEngine` SHALL NOT throw any exception caused by per-user step failures, regardless of how many users failed.
4. IF `HttpTransportLayer.execute(step)` throws an `InterruptedException` for a given `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL re-interrupt the Virtual_Thread via `Thread.currentThread().interrupt()`, exit the step loop for that user, and record `VirtualUserState.COMPLETED` for that `userId` in the User_State_Registry, treating the interruption as a clean stop signal rather than a failure.

---

### Requirement 5: Thread-Safe Metrics Collection Hooks

**User Story:** As a developer, I want each completed HTTP request to be recorded via the MetricsEngine, so that latency and success/failure data is captured concurrently without data corruption.

#### Acceptance Criteria

1. WHEN `HttpTransportLayer.execute(step)` returns an `HttpResponseResult` for any `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL immediately call `MetricsEngine.record(result.latencyNanos(), success)` — before checking the Shutdown_Signal or Pause_Latch — where `success` is `true` if `result.statusCode()` is in the range [200, 299] inclusive, and `false` for any other status code including `0`, `4xx`, and `5xx`; `MetricsEngine.record` MUST be invoked for every result object successfully returned from `HttpTransportLayer.execute`, including results with status code `0`.
2. THE `DefaultVirtualUserEngine` SHALL accept a `MetricsEngine` instance at construction time; IF the provided `MetricsEngine` is null, THE `DefaultVirtualUserEngine` SHALL throw a `NullPointerException` in the constructor before any other initialization.
3. THE `DefaultVirtualUserEngine` SHALL call `MetricsEngine.record` from within the Virtual_Thread of the user that executed the step, such that `Thread.currentThread()` inside the `record` call is the same Virtual_Thread that performed the corresponding `HttpTransportLayer.execute` call.
4. WHEN `HttpTransportLayer.execute(step)` throws an exception for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL NOT call `MetricsEngine.record` for that failed step invocation.
5. WHEN `MetricsEngine.record(latencyNanos, success)` throws any exception for a given `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL treat it as a fatal error for that user: catch the exception within the Virtual_Thread, log it at ERROR level including the `userId`, record `VirtualUserState.FAILED` for that `userId`, and terminate that user's step loop without propagating the exception to any other Virtual_Thread.

---

### Requirement 6: Graceful Shutdown

**User Story:** As a developer, I want the engine to shut down cleanly when stop is requested, so that in-flight requests complete and no resources are leaked.

#### Acceptance Criteria

1. WHEN `stop()` is called on `DefaultVirtualUserEngine`, THE `DefaultVirtualUserEngine` SHALL set the Shutdown_Signal to `true` using an atomic write (e.g., `AtomicBoolean.set(true)`) so that all Virtual_Threads observe the updated value at their next inter-step boundary check.
2. WHEN the Shutdown_Signal is set, each Virtual_Thread SHALL complete its current in-flight `HttpTransportLayer.execute` call before checking the signal — the signal SHALL NOT interrupt a call already in progress, and no `Thread.interrupt()` SHALL be issued to the Virtual_Thread by the shutdown path.
3. WHEN `stop()` is called, THE `DefaultVirtualUserEngine` SHALL also release any Virtual_Threads blocked on the Pause_Latch by counting down the latch, so that paused users can observe the Shutdown_Signal and exit cleanly.
4. WHEN `execute(List<VirtualUser> users, Scenario scenario)` returns after all Virtual_Threads have terminated following a `stop()` call, THE `DefaultVirtualUserEngine` SHALL log at INFO level a message indicating that all virtual users have stopped; this log SHALL occur within `execute` after all threads join, not within `stop()`.
5. WHEN `stop()` is called before `execute` is invoked, THE `DefaultVirtualUserEngine` SHALL set the Shutdown_Signal flag to `true` such that any subsequent `execute` call observes the flag and returns immediately without launching any threads.
6. WHEN `stop()` is called more than once, THE `DefaultVirtualUserEngine` SHALL complete without throwing an exception on any invocation (idempotent stop).
7. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called after `stop()` has already been invoked on the same `DefaultVirtualUserEngine` instance, THE `DefaultVirtualUserEngine` SHALL return immediately without launching any threads, and `getStates()` SHALL return an empty map (or the map from any prior `execute` call if one occurred).

---

### Requirement 7: Load Scheduler Integration — Ramp-Up Profile

**User Story:** As a developer, I want virtual users to be activated gradually during the ramp-up window, so that the target system is not hit with full load instantaneously.

#### Acceptance Criteria

1. THE `DefaultLoadScheduler` SHALL be a class in `com.latencylab.scheduler` that declares `implements LoadScheduler`, verifiable by `LoadScheduler.class.isAssignableFrom(DefaultLoadScheduler.class)` returning `true`.
2. WHEN `DefaultLoadScheduler.start(Scenario scenario)` is called and `Scenario.rampUpSeconds` is greater than 0, THE `DefaultLoadScheduler` SHALL activate virtual users one at a time with an inter-activation delay of `(rampUpSeconds * 1000L) / userCount` milliseconds (integer division, rounded down, minimum 0 ms) between each activation, where "activate" means invoking the registered activation callback or incrementing the active-user counter by 1.
3. WHEN `DefaultLoadScheduler.start(Scenario scenario)` is called and `Scenario.rampUpSeconds` is 0, THE `DefaultLoadScheduler` SHALL activate all virtual users immediately with no inter-activation delay (Constant_Load_Profile), equivalent to a delay of 0 ms.
4. WHEN `DefaultLoadScheduler.start(Scenario scenario)` is called, THE `DefaultLoadScheduler` SHALL transition its internal state to `SchedulerState.RUNNING` immediately at the start of the `start()` method — before activating any users — such that `getState()` returns `SchedulerState.RUNNING` before the first activation callback is invoked.
5. WHEN `DefaultLoadScheduler.pause()` is called while the scheduler is in `SchedulerState.RUNNING`, THE `DefaultLoadScheduler` SHALL transition its state to `SchedulerState.PAUSED` and suspend the activation of any remaining unactivated users until `resume()` is called or `stop()` is called.
6. WHEN `DefaultLoadScheduler.stop()` is called, THE `DefaultLoadScheduler` SHALL transition its state to `SchedulerState.STOPPED` and cancel any pending user activations, such that no further activation callbacks are invoked after `stop()` returns.
7. WHEN `DefaultLoadScheduler.getState()` is called, THE `DefaultLoadScheduler` SHALL return the current `SchedulerState` reflecting the most recent lifecycle transition, with no caching lag.
8. IF `DefaultLoadScheduler.start(Scenario scenario)` is called with a null `Scenario`, THE `DefaultLoadScheduler` SHALL throw a `NullPointerException` before performing any scheduler state validation and before transitioning state or activating any users; parameter validation MUST occur before state validation, so a null `Scenario` always throws `NullPointerException` regardless of the current `SchedulerState`.
9. IF `DefaultLoadScheduler.start(Scenario scenario)` is called when the scheduler is already in `SchedulerState.RUNNING`, THE `DefaultLoadScheduler` SHALL throw an `IllegalStateException` without activating any additional users or changing the current state.

---

### Requirement 8: Load Scheduler Integration — Constant Load Profile

**User Story:** As a developer, I want a constant load profile that activates all virtual users at once, so that I can test peak-load behavior without a ramp-up period.

#### Acceptance Criteria

1. WHEN `DefaultLoadScheduler.start(Scenario scenario)` is called with `Scenario.rampUpSeconds == 0`, THE `DefaultLoadScheduler` SHALL activate all `Scenario.userCount` virtual users — meaning it invokes the registered activation callback or increments the active-user counter exactly `userCount` times — in a single synchronous loop with no `Thread.sleep` or other delay between activations.
2. WHEN `DefaultLoadScheduler.start(Scenario scenario)` is called with `Scenario.rampUpSeconds == 0` and the scheduler transitions to `SchedulerState.RUNNING`, THE `DefaultLoadScheduler` SHALL remain in `SchedulerState.RUNNING` until `stop()` is explicitly called, such that `getState()` returns `SchedulerState.RUNNING` at any point before `stop()` is invoked.
3. WHEN `DefaultLoadScheduler.stop()` is called while in `SchedulerState.RUNNING` under the Constant_Load_Profile, THE `DefaultLoadScheduler` SHALL transition to `SchedulerState.STOPPED` synchronously within the `stop()` call, such that `getState()` returns `SchedulerState.STOPPED` before `stop()` returns.

---

### Requirement 9: Build and Interface Compliance

**User Story:** As a developer, I want the Phase 3 implementations to be verifiable by the existing build and test infrastructure, so that the project remains in a `BUILD SUCCESS` state after Phase 3 is merged.

#### Acceptance Criteria

1. WHEN `mvn verify` is executed on the Phase 3 codebase, THE Maven_Build SHALL complete with `BUILD SUCCESS`; any compilation error, test failure, or missing required test class SHALL cause the build to fail with a non-zero exit code.
2. THE `DefaultVirtualUserEngine` class SHALL satisfy `VirtualUserEngine.class.isAssignableFrom(DefaultVirtualUserEngine.class)` returning `true`, verifiable by a JUnit 5 reflection test.
3. THE `DefaultLoadScheduler` class SHALL satisfy `LoadScheduler.class.isAssignableFrom(DefaultLoadScheduler.class)` returning `true`, verifiable by a JUnit 5 reflection test.
4. THE Maven_Build SHALL include JUnit 5 unit tests for `DefaultVirtualUserEngine` that assert:
   - (a) concurrent execution invokes `HttpTransportLayer.execute` exactly once per step per user across all users;
   - (b) a failing user (one whose transport throws an exception) does not prevent other users from completing all their steps;
   - (c) `pause()` blocks Virtual_Threads such that no further `HttpTransportLayer.execute` calls are made until `resume()` is called;
   - (d) `stop()` causes each Virtual_Thread to complete its current in-flight call and not invoke `HttpTransportLayer.execute` for any further steps;
   - (e) `MetricsEngine.record` is called once per returned `HttpResponseResult` (including non-2xx results) and is NOT called when `HttpTransportLayer.execute` throws an exception.
5. THE Maven_Build SHALL include JUnit 5 unit tests for `DefaultLoadScheduler` that assert:
   - (a) `getState()` returns `IDLE` before `start()` is called, `RUNNING` after `start()` is called, `PAUSED` after `pause()` is called, and `STOPPED` after `stop()` is called;
   - (b) the ramp-up profile produces an inter-activation delay of `(rampUpSeconds * 1000L) / userCount` ms (integer division, minimum 0) between consecutive user activations.
6. THE Maven_Build SHALL fail with a non-zero exit code for any of the following reasons: missing required test classes, compilation errors, test failures, or zero discovered tests; `failIfNoTests=true` SHALL be enforced in Maven Surefire to cover the zero-test case.
7. THE Maven_Build SHALL include the following test classes, each containing at least one `@Test` method; absence of any of these classes SHALL cause the build to fail: `DefaultVirtualUserEngineTest`, `DefaultVirtualUserEngineComplianceTest`, and `DefaultLoadSchedulerTest`.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

This feature involves concurrent state machines, thread-safe counters, lifecycle transitions, and scheduler arithmetic — all well-suited for property-based testing. The chosen PBT library is **[jqwik](https://jqwik.net/)** (already in `pom.xml` at version 1.8.4, JUnit 5 compatible). Each property test runs a minimum of 100 iterations.

**Property Reflection — Redundancy Check (performed before writing properties):**

- Properties about metrics record count (from Req 5.1 and 5.2) share the same invariant — merged into one property covering both success and failure status codes.
- Properties about User_State_Registry size (from Req 2.1–2.3) and state correctness are complementary but distinct — kept separate.
- Properties about ramp-up delay arithmetic (Req 7.2) and constant load (Req 8.1) are distinct — kept separate.
- Properties about stop idempotency (Req 6.6) and pause/resume symmetry (Req 2.4–2.5) are distinct — kept separate.
- Properties about error isolation (Req 4.1–4.3) and metrics not being called on exception (Req 5.5) are distinct — kept separate.

After reflection, 8 unique properties remain.

---

### Property 1: MetricsEngine.record is called exactly once per returned HttpResponseResult

*For any* list of N virtual users each executing a scenario with K steps, where all transport calls return successfully, `MetricsEngine.record` SHALL be called exactly N × K times after `execute` returns.

**Validates: Requirements 5.1, 5.2**

---

### Property 2: MetricsEngine.record success flag matches HTTP status code range

*For any* `HttpResponseResult` with a `statusCode` in [200, 299], `MetricsEngine.record` SHALL be called with `success = true`; *for any* `statusCode` outside [200, 299] (including 0 for network failures), `MetricsEngine.record` SHALL be called with `success = false`.

**Validates: Requirements 5.1, 5.2**

---

### Property 3: User_State_Registry contains exactly one entry per virtual user after execute completes

*For any* list of N virtual users, after `execute` returns, the User_State_Registry SHALL contain exactly N entries — one per `userId` — and each entry SHALL be in either `COMPLETED` or `FAILED` state (never `IDLE` or `RUNNING`).

**Validates: Requirements 2.1, 2.2, 2.3**

---

### Property 4: Per-user exception isolation — failing users do not reduce the metrics record count for other users

*For any* list of N virtual users where exactly one user's transport call always throws an exception, `MetricsEngine.record` SHALL be called exactly (N - 1) × K times (where K is the step count), and `execute` SHALL return normally without throwing.

**Validates: Requirements 4.1, 4.2, 4.3, 5.5**

---

### Property 5: Ramp-up inter-batch delay is non-negative for all valid rampUpSeconds and userCount values

*For any* `rampUpSeconds` in [0, 3600] and `userCount` in [1, 100000], the computed inter-batch delay `(rampUpSeconds * 1000) / userCount` SHALL be greater than or equal to 0 and SHALL never cause an arithmetic exception.

**Validates: Requirement 7.2**

---

### Property 6: Constant load profile activates all users in a single batch

*For any* `Scenario` with `rampUpSeconds == 0` and `userCount` in [1, 100000], `DefaultLoadScheduler.start(scenario)` SHALL activate all `userCount` users without any inter-batch sleep, and the scheduler SHALL transition to `SchedulerState.RUNNING`.

**Validates: Requirements 8.1, 8.2**

---

### Property 7: stop() is idempotent — calling it N times never throws

*For any* number of `stop()` invocations (≥ 2) on the same `DefaultVirtualUserEngine` instance, every call after the first SHALL complete without throwing any exception.

**Validates: Requirement 6.6**

---

### Property 8: getStates() snapshot size equals the number of users passed to execute

*For any* list of N virtual users passed to `execute`, after `execute` returns, `getStates()` SHALL return a map of exactly N entries, and the key set SHALL equal the set of `userId` values from the input list.

**Validates: Requirement 2.8**

---
