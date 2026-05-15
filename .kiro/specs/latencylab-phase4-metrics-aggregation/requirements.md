# Requirements Document

## Introduction

Phase 4 of LatencyLab implements the Metrics Aggregation Engine — the concrete runtime layer that turns the Phase 1 `MetricsEngine` interface contract into a production-grade, thread-safe aggregation implementation. This phase delivers `DefaultMetricsEngine` in `com.latencylab.metrics`, which receives per-request outcomes from concurrent virtual threads (via `MetricsEngine.record`), aggregates them in real time, and produces point-in-time `MetricsSnapshot` values on demand (via `MetricsEngine.snapshot`).

The aggregated metrics cover three domains:

- **Latency statistics**: average, minimum, maximum, P50, P95, and P99 percentiles (all in nanoseconds)
- **Throughput metrics**: requests per second (RPS)
- **Error rate tracking**: total, successful, and failed request counts

Phase 4 builds directly on Phase 3's `DefaultVirtualUserEngine`, which already calls `MetricsEngine.record` for every completed HTTP request from within virtual threads. No changes to `DefaultVirtualUserEngine` are required in this phase — the engine already provides the correct call site. Phase 4 simply provides the implementation that makes those calls meaningful.

No reporting or assertion logic is implemented in this phase — those arrive in Phases 5 and 6.

---

## Glossary

- **DefaultMetricsEngine**: The concrete class in `com.latencylab.metrics` that implements `MetricsEngine`, providing thread-safe real-time aggregation of per-request latency and success/failure outcomes.
- **MetricsEngine**: The Phase 1 interface in `com.latencylab.metrics` with methods `record(long latencyNanos, boolean success): void` and `snapshot(): MetricsSnapshot`.
- **MetricsSnapshot**: The Phase 1 record in `com.latencylab.model` containing: `totalRequests` (long), `successfulRequests` (long), `failedRequests` (long), `avgLatencyNanos` (long), `minLatencyNanos` (long), `maxLatencyNanos` (long), `p50LatencyNanos` (long), `p95LatencyNanos` (long), `p99LatencyNanos` (long), `requestsPerSecond` (double), and `snapshotTimestamp` (long).
- **Latency_Sample**: A single `latencyNanos` value (long, ≥ 0) recorded by one call to `MetricsEngine.record`, representing the wall-clock duration of one HTTP request measured in nanoseconds via `System.nanoTime()` deltas.
- **Latency_Buffer**: The internal concurrent data structure in `DefaultMetricsEngine` that accumulates all Latency_Samples for percentile computation. Must support concurrent appends from multiple virtual threads without data loss or corruption.
- **Running_Sum**: The internal `AtomicLong` accumulator in `DefaultMetricsEngine` that holds the sum of all recorded `latencyNanos` values, used to compute `avgLatencyNanos` at snapshot time.
- **Running_Min**: The internal `AtomicLong` in `DefaultMetricsEngine` that tracks the minimum recorded `latencyNanos` value across all calls to `record`.
- **Running_Max**: The internal `AtomicLong` in `DefaultMetricsEngine` that tracks the maximum recorded `latencyNanos` value across all calls to `record`.
- **Total_Counter**: The internal `AtomicLong` in `DefaultMetricsEngine` that counts every call to `record`, regardless of the `success` flag.
- **Success_Counter**: The internal `AtomicLong` in `DefaultMetricsEngine` that counts every call to `record` where `success == true`.
- **Failure_Counter**: The internal `AtomicLong` in `DefaultMetricsEngine` that counts every call to `record` where `success == false`.
- **Start_Timestamp**: The `System.nanoTime()` value captured at `DefaultMetricsEngine` construction time, used as the denominator reference for RPS calculation.
- **Elapsed_Seconds**: The duration in seconds from Start_Timestamp to the moment `snapshot()` is called, computed as `(System.nanoTime() - startTimestamp) / 1_000_000_000.0`.
- **RPS**: Requests Per Second — computed as `totalRequests / Elapsed_Seconds` at snapshot time; defined as `0.0` when Elapsed_Seconds is zero or when no requests have been recorded.
- **Percentile**: A value from the sorted Latency_Buffer at a specific rank position. P50 is the value at index `(int) Math.floor(0.50 * n)`, P95 at `(int) Math.floor(0.95 * n)`, P99 at `(int) Math.floor(0.99 * n)`, where `n` is the total number of recorded samples (0-indexed, sorted ascending).
- **Zero_State_Snapshot**: A `MetricsSnapshot` returned by `snapshot()` when no calls to `record` have been made, with all numeric fields set to `0` and `requestsPerSecond` set to `0.0`.
- **Virtual_Thread**: A Java 21 Project Loom lightweight thread, as used by `DefaultVirtualUserEngine` to execute virtual user scenarios concurrently.
- **HttpResponseResult**: The Phase 1 record in `com.latencylab.transport` containing `statusCode` (int), `responseBody` (String, nullable), and `latencyNanos` (long).
- **DefaultVirtualUserEngine**: The Phase 3 concrete class in `com.latencylab.engine` that calls `MetricsEngine.record(result.latencyNanos(), success)` for every `HttpResponseResult` returned by the transport layer.

---

## Requirements

### Requirement 1: DefaultMetricsEngine — Class Structure and Interface Compliance

**User Story:** As a developer, I want a concrete `MetricsEngine` implementation that satisfies the Phase 1 interface contract, so that the existing `DefaultVirtualUserEngine` can be wired to a real aggregation engine without any changes to the engine or transport layer.

#### Acceptance Criteria

1. THE `DefaultMetricsEngine` SHALL be a class in `com.latencylab.metrics` that declares `implements MetricsEngine`, verifiable by `MetricsEngine.class.isAssignableFrom(DefaultMetricsEngine.class)` returning `true`.
2. THE `DefaultMetricsEngine` SHALL provide a public no-argument constructor that initializes all internal counters and accumulators to their zero states and captures the Start_Timestamp via `System.nanoTime()`.
3. THE `DefaultMetricsEngine` SHALL declare `private static final Logger log` obtained via `LoggerFactory.getLogger(DefaultMetricsEngine.class)` for SLF4J-based logging.
4. THE `DefaultMetricsEngine` SHALL hold the following internal fields: a `Total_Counter` (`AtomicLong`), a `Success_Counter` (`AtomicLong`), a `Failure_Counter` (`AtomicLong`), a `Running_Sum` (`AtomicLong`), a `Running_Min` (`AtomicLong` initialized to `Long.MAX_VALUE`), a `Running_Max` (`AtomicLong` initialized to `0`), a `Latency_Buffer` (a `java.util.concurrent.CopyOnWriteArrayList<Long>` or equivalent thread-safe list), and a `startTimestamp` (`long`, captured at construction via `System.nanoTime()`).
5. WHEN `DefaultMetricsEngine` is constructed, THE `DefaultMetricsEngine` SHALL log at DEBUG level a message indicating that the metrics engine has been initialized, including the Start_Timestamp value.

---

### Requirement 2: Thread-Safe Metrics Recording

**User Story:** As a developer, I want `MetricsEngine.record` to be safely callable from hundreds of concurrent virtual threads simultaneously, so that no latency samples or request counts are lost or corrupted under high concurrency.

#### Acceptance Criteria

1. WHEN `record(long latencyNanos, boolean success)` is called from any thread, THE `DefaultMetricsEngine` SHALL atomically increment the `Total_Counter` by 1, such that after N concurrent calls to `record` from N distinct threads, `Total_Counter.get()` equals N with no lost increments.
2. WHEN `record(long latencyNanos, boolean success)` is called with `success == true`, THE `DefaultMetricsEngine` SHALL atomically increment the `Success_Counter` by 1; WHEN called with `success == false`, THE `DefaultMetricsEngine` SHALL atomically increment the `Failure_Counter` by 1; at all times, `Success_Counter.get() + Failure_Counter.get()` SHALL equal `Total_Counter.get()`.
3. WHEN `record(long latencyNanos, boolean success)` is called, THE `DefaultMetricsEngine` SHALL atomically add `latencyNanos` to the `Running_Sum` using `AtomicLong.addAndGet`.
4. WHEN `record(long latencyNanos, boolean success)` is called, THE `DefaultMetricsEngine` SHALL update the `Running_Min` using a compare-and-set loop: IF `latencyNanos < Running_Min.get()`, THE `DefaultMetricsEngine` SHALL attempt to set `Running_Min` to `latencyNanos` via `AtomicLong.compareAndSet`, retrying until the CAS succeeds or the current minimum is already less than or equal to `latencyNanos`.
5. WHEN `record(long latencyNanos, boolean success)` is called, THE `DefaultMetricsEngine` SHALL update the `Running_Max` using a compare-and-set loop: IF `latencyNanos > Running_Max.get()`, THE `DefaultMetricsEngine` SHALL attempt to set `Running_Max` to `latencyNanos` via `AtomicLong.compareAndSet`, retrying until the CAS succeeds or the current maximum is already greater than or equal to `latencyNanos`.
6. WHEN `record(long latencyNanos, boolean success)` is called, THE `DefaultMetricsEngine` SHALL append `latencyNanos` to the `Latency_Buffer` using a thread-safe append operation, such that no sample is lost under concurrent appends from multiple Virtual_Threads.
7. WHEN `record(long latencyNanos, boolean success)` is called with `latencyNanos` equal to `0`, THE `DefaultMetricsEngine` SHALL accept the value without throwing an exception and SHALL include it in all aggregations (sum, min, max, buffer).
8. WHEN `record(long latencyNanos, boolean success)` is called with `latencyNanos` equal to `Long.MAX_VALUE`, THE `DefaultMetricsEngine` SHALL accept the value without throwing an exception and SHALL include it in all aggregations.
9. IF `record(long latencyNanos, boolean success)` is called with `latencyNanos` less than `0`, THE `DefaultMetricsEngine` SHALL throw an `IllegalArgumentException` whose message contains the word `"latencyNanos"` and the received value, and SHALL NOT update any internal counter or accumulator.
10. WHEN `record(long latencyNanos, boolean success)` completes successfully, THE `DefaultMetricsEngine` SHALL log at DEBUG level a message containing the `latencyNanos` value and the `success` flag.

---

### Requirement 3: Latency Statistics Computation

**User Story:** As a developer, I want `MetricsEngine.snapshot()` to return accurate latency statistics (avg, min, max, P50, P95, P99) computed from all recorded samples, so that I can evaluate backend latency distribution at any point during or after a test run.

#### Acceptance Criteria

1. WHEN `snapshot()` is called and `Total_Counter.get()` is greater than 0, THE `DefaultMetricsEngine` SHALL compute `avgLatencyNanos` as `Running_Sum.get() / Total_Counter.get()` using integer division (truncating toward zero), and SHALL include this value in the returned `MetricsSnapshot`.
2. WHEN `snapshot()` is called and `Total_Counter.get()` is greater than 0, THE `DefaultMetricsEngine` SHALL set `minLatencyNanos` to `Running_Min.get()` in the returned `MetricsSnapshot`.
3. WHEN `snapshot()` is called and `Total_Counter.get()` is greater than 0, THE `DefaultMetricsEngine` SHALL set `maxLatencyNanos` to `Running_Max.get()` in the returned `MetricsSnapshot`.
4. WHEN `snapshot()` is called and `Total_Counter.get()` is greater than 0, THE `DefaultMetricsEngine` SHALL compute percentiles by: (a) copying the `Latency_Buffer` into a local `long[]` array, (b) sorting the array in ascending order using `Arrays.sort`, (c) computing P50 as `sortedArray[(int) Math.floor(0.50 * n)]`, P95 as `sortedArray[(int) Math.floor(0.95 * n)]`, and P99 as `sortedArray[(int) Math.floor(0.99 * n)]`, where `n = sortedArray.length`; the sort and index computation SHALL be performed on the local copy and SHALL NOT modify the `Latency_Buffer`.
5. WHEN `snapshot()` is called and `Total_Counter.get()` is exactly 1, THE `DefaultMetricsEngine` SHALL return a `MetricsSnapshot` where `avgLatencyNanos`, `minLatencyNanos`, `maxLatencyNanos`, `p50LatencyNanos`, `p95LatencyNanos`, and `p99LatencyNanos` all equal the single recorded `latencyNanos` value.
6. WHEN `snapshot()` is called and `Total_Counter.get()` is 0 (Zero_State_Snapshot), THE `DefaultMetricsEngine` SHALL return a `MetricsSnapshot` with `avgLatencyNanos = 0`, `minLatencyNanos = 0`, `maxLatencyNanos = 0`, `p50LatencyNanos = 0`, `p95LatencyNanos = 0`, `p99LatencyNanos = 0`, `totalRequests = 0`, `successfulRequests = 0`, `failedRequests = 0`, and `requestsPerSecond = 0.0`.
7. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL ensure that `minLatencyNanos <= avgLatencyNanos` and `avgLatencyNanos <= maxLatencyNanos` hold in the returned `MetricsSnapshot` for any non-empty set of recorded samples.
8. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL ensure that `p50LatencyNanos <= p95LatencyNanos` and `p95LatencyNanos <= p99LatencyNanos` hold in the returned `MetricsSnapshot` for any non-empty set of recorded samples.

---

### Requirement 4: Throughput Metrics Computation

**User Story:** As a developer, I want `MetricsEngine.snapshot()` to include an accurate requests-per-second (RPS) value, so that I can evaluate backend throughput at any point during or after a test run.

#### Acceptance Criteria

1. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL compute `Elapsed_Seconds` as `(System.nanoTime() - startTimestamp) / 1_000_000_000.0`, where `startTimestamp` is the `System.nanoTime()` value captured at construction time.
2. WHEN `snapshot()` is called and `Elapsed_Seconds` is greater than 0 and `Total_Counter.get()` is greater than 0, THE `DefaultMetricsEngine` SHALL compute `requestsPerSecond` as `(double) Total_Counter.get() / Elapsed_Seconds` and include this value in the returned `MetricsSnapshot`.
3. WHEN `snapshot()` is called and `Elapsed_Seconds` is 0 or `Total_Counter.get()` is 0, THE `DefaultMetricsEngine` SHALL set `requestsPerSecond` to `0.0` in the returned `MetricsSnapshot` to avoid division by zero.
4. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL NOT use `System.currentTimeMillis()` for elapsed time computation; all time measurements SHALL use `System.nanoTime()`.
5. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL ensure that `requestsPerSecond` is greater than or equal to `0.0` in the returned `MetricsSnapshot`.

---

### Requirement 5: Error Rate Tracking

**User Story:** As a developer, I want `MetricsEngine.snapshot()` to accurately reflect the total, successful, and failed request counts, so that I can compute error rates and evaluate backend reliability during a test run.

#### Acceptance Criteria

1. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL set `totalRequests` to `Total_Counter.get()`, `successfulRequests` to `Success_Counter.get()`, and `failedRequests` to `Failure_Counter.get()` in the returned `MetricsSnapshot`.
2. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL ensure that `totalRequests == successfulRequests + failedRequests` holds in the returned `MetricsSnapshot` at all times, including under concurrent recording.
3. WHEN `record(latencyNanos, true)` is called N times and `record(latencyNanos, false)` is called M times, THEN after all calls complete, `snapshot().successfulRequests` SHALL equal N, `snapshot().failedRequests` SHALL equal M, and `snapshot().totalRequests` SHALL equal N + M.
4. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL ensure that `successfulRequests` is greater than or equal to 0 and `failedRequests` is greater than or equal to 0 in the returned `MetricsSnapshot`.

---

### Requirement 6: Snapshot Consistency and Timestamp

**User Story:** As a developer, I want each `MetricsSnapshot` to be internally consistent and timestamped, so that snapshots taken at different points in time can be compared and correlated with test execution events.

#### Acceptance Criteria

1. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL set `snapshotTimestamp` to the value of `System.nanoTime()` captured at the moment `snapshot()` is invoked, such that the timestamp reflects the wall-clock time of the snapshot creation.
2. WHEN `snapshot()` is called twice in succession with no intervening `record()` calls, THE `DefaultMetricsEngine` SHALL return two `MetricsSnapshot` instances where all fields except `snapshotTimestamp` and `requestsPerSecond` are equal, and the second `snapshotTimestamp` is greater than or equal to the first.
3. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL construct the `MetricsSnapshot` record using a single consistent read of all internal counters and accumulators, such that the returned snapshot does not reflect a partially-updated state caused by a concurrent `record()` call mid-snapshot.
4. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL log at DEBUG level a message indicating that a snapshot was taken, including the `totalRequests` and `requestsPerSecond` values.
5. WHEN `snapshot()` is called, THE `DefaultMetricsEngine` SHALL NOT reset or clear any internal counters, accumulators, or the `Latency_Buffer`; all subsequent `snapshot()` calls SHALL reflect the cumulative state of all `record()` calls made since construction.

---

### Requirement 7: Integration with DefaultVirtualUserEngine

**User Story:** As a developer, I want `DefaultMetricsEngine` to integrate seamlessly with the existing `DefaultVirtualUserEngine`, so that a complete end-to-end test run produces an accurate `MetricsSnapshot` reflecting all HTTP requests executed by all virtual users.

#### Acceptance Criteria

1. WHEN `DefaultVirtualUserEngine` is constructed with a `DefaultMetricsEngine` instance and `execute(users, scenario)` is called with N virtual users each executing a scenario with K steps, and all transport calls return successfully, THEN after `execute` returns, `metricsEngine.snapshot().totalRequests` SHALL equal N × K.
2. WHEN `DefaultVirtualUserEngine.execute` completes and some virtual users failed (transport threw exceptions), THEN `metricsEngine.snapshot().totalRequests` SHALL equal the number of `HttpResponseResult` objects successfully returned by the transport layer across all users, consistent with Phase 3 Requirement 5.1.
3. WHEN `DefaultVirtualUserEngine.execute` completes with a mix of 2xx and non-2xx responses, THEN `metricsEngine.snapshot().successfulRequests` SHALL equal the count of responses with `statusCode` in [200, 299], and `metricsEngine.snapshot().failedRequests` SHALL equal the count of responses with `statusCode` outside [200, 299] (including `statusCode == 0`).
4. WHEN `DefaultVirtualUserEngine.execute` completes, THEN `metricsEngine.snapshot().minLatencyNanos` SHALL equal the minimum `latencyNanos` value across all recorded `HttpResponseResult` objects, and `metricsEngine.snapshot().maxLatencyNanos` SHALL equal the maximum.
5. WHEN `DefaultVirtualUserEngine` is constructed with a `DefaultMetricsEngine` instance, THE `DefaultMetricsEngine` SHALL be passed as the `MetricsEngine` argument to the `DefaultVirtualUserEngine` constructor; no changes to `DefaultVirtualUserEngine` are required in Phase 4.

---

### Requirement 8: Build and Interface Compliance

**User Story:** As a developer, I want the Phase 4 implementation to be verifiable by the existing build and test infrastructure, so that the project remains in a `BUILD SUCCESS` state after Phase 4 is merged.

#### Acceptance Criteria

1. WHEN `mvn verify` is executed on the Phase 4 codebase, THE Maven_Build SHALL complete with `BUILD SUCCESS`; any compilation error, test failure, or missing required test class SHALL cause the build to fail with a non-zero exit code.
2. THE `DefaultMetricsEngine` class SHALL satisfy `MetricsEngine.class.isAssignableFrom(DefaultMetricsEngine.class)` returning `true`, verifiable by a JUnit 5 reflection test in `com.latencylab.metrics` under `src/test/java`.
3. THE Maven_Build SHALL include JUnit 5 unit tests for `DefaultMetricsEngine` in `com.latencylab.metrics` that assert:
   - (a) `record` increments `totalRequests` by 1 per call;
   - (b) `record(latencyNanos, true)` increments `successfulRequests` and `record(latencyNanos, false)` increments `failedRequests`;
   - (c) `snapshot()` on a zero-state engine returns all-zero fields;
   - (d) `snapshot()` after a single `record(1000L, true)` returns `avgLatencyNanos = 1000`, `minLatencyNanos = 1000`, `maxLatencyNanos = 1000`, `p50LatencyNanos = 1000`, `p95LatencyNanos = 1000`, `p99LatencyNanos = 1000`;
   - (e) `record(-1L, true)` throws `IllegalArgumentException`;
   - (f) `snapshot().totalRequests == snapshot().successfulRequests + snapshot().failedRequests` after a mixed sequence of success and failure records.
4. THE Maven_Build SHALL include JUnit 5 unit tests for `DefaultMetricsEngine` that verify thread-safety: spawn 50 virtual threads each calling `record(1000L, true)` once, join all threads, assert `snapshot().totalRequests == 50` and `snapshot().successfulRequests == 50`.
5. THE Maven_Build SHALL include the following test classes, each containing at least one `@Test` method; absence of any of these classes SHALL cause the build to fail: `DefaultMetricsEngineTest` and `DefaultMetricsEngineComplianceTest`.
6. THE Maven_Build SHALL fail with a non-zero exit code for any of the following reasons: missing required test classes, compilation errors, test failures, or zero discovered tests; `failIfNoTests=true` SHALL be enforced in Maven Surefire.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

This feature involves concurrent atomic counters, statistical aggregation over arbitrary data sets, and percentile computation — all well-suited for property-based testing. The chosen PBT library is **[jqwik](https://jqwik.net/)** (already in `pom.xml` at version 1.8.4, JUnit 5 compatible). Each property test runs a minimum of 100 iterations.

**Property Reflection — Redundancy Check (performed before writing properties):**

- Properties about total/success/failure counter invariants (Req 2.1, 2.2, 5.1, 5.2) share the same core invariant — merged into one property covering the counter consistency invariant.
- Properties about avg/min/max ordering (Req 3.7) and percentile ordering (Req 3.8) are distinct mathematical invariants — kept separate.
- Properties about concurrent recording correctness (Req 2.1, 7.1) are distinct from single-threaded statistical correctness — kept separate.
- Properties about snapshot idempotency (Req 6.2, 6.5) are distinct from counter invariants — kept separate.
- Properties about RPS non-negativity (Req 4.5) are a simple invariant over any input — kept as a standalone property.
- Properties about percentile index formula correctness (Req 3.4) are testable over arbitrary sorted arrays — kept as a standalone property.

After reflection, 7 unique properties remain.

---

### Property 1: Counter consistency invariant — totalRequests == successfulRequests + failedRequests

*For any* sequence of N calls to `record(latencyNanos, success)` with arbitrary `success` values, after all calls complete, `snapshot().totalRequests` SHALL equal `snapshot().successfulRequests + snapshot().failedRequests`, and `snapshot().totalRequests` SHALL equal N.

**Validates: Requirements 2.1, 2.2, 5.1, 5.2, 5.3**

---

### Property 2: Latency ordering invariant — min ≤ avg ≤ max and P50 ≤ P95 ≤ P99

*For any* non-empty list of latency values recorded via `record`, the returned `MetricsSnapshot` SHALL satisfy `minLatencyNanos <= avgLatencyNanos`, `avgLatencyNanos <= maxLatencyNanos`, `p50LatencyNanos <= p95LatencyNanos`, and `p95LatencyNanos <= p99LatencyNanos`.

**Validates: Requirements 3.7, 3.8**

---

### Property 3: Percentile index formula correctness

*For any* sorted `long[]` array of length N (N ≥ 1) with values in [0, Long.MAX_VALUE], the percentile at rank R (where R ∈ {0.50, 0.95, 0.99}) computed as `array[(int) Math.floor(R * N)]` SHALL be a value that exists in the array, SHALL be ≥ `array[0]`, and SHALL be ≤ `array[N-1]`.

**Validates: Requirement 3.4**

---

### Property 4: Thread-safe concurrent recording — no lost updates

*For any* `threadCount` in [2, 50] and `recordsPerThread` in [1, 100], when `threadCount` virtual threads each call `record(1000L, true)` exactly `recordsPerThread` times concurrently, after all threads complete, `snapshot().totalRequests` SHALL equal `threadCount × recordsPerThread` with no lost increments.

**Validates: Requirements 2.1, 2.3, 7.1**

---

### Property 5: RPS is non-negative for all valid inputs

*For any* number of `record` calls (including zero), `snapshot().requestsPerSecond` SHALL be greater than or equal to `0.0` and SHALL NOT be `NaN` or infinite.

**Validates: Requirements 4.3, 4.5**

---

### Property 6: Snapshot idempotency — repeated snapshots without new records return equivalent data

*For any* sequence of N `record` calls, calling `snapshot()` twice in succession with no intervening `record` calls SHALL return two `MetricsSnapshot` instances where `totalRequests`, `successfulRequests`, `failedRequests`, `avgLatencyNanos`, `minLatencyNanos`, `maxLatencyNanos`, `p50LatencyNanos`, `p95LatencyNanos`, and `p99LatencyNanos` are all equal between the two snapshots.

**Validates: Requirements 6.2, 6.5**

---

### Property 7: Min and max correctness — min equals the smallest recorded value, max equals the largest

*For any* non-empty list of latency values L recorded via `record`, `snapshot().minLatencyNanos` SHALL equal `Collections.min(L)` and `snapshot().maxLatencyNanos` SHALL equal `Collections.max(L)`.

**Validates: Requirements 3.2, 3.3**
