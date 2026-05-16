# Implementation Plan: Phase 4 — Metrics Aggregation

## Overview

Implement `DefaultMetricsEngine` in `com.latencylab.metrics` — a lock-free, thread-safe concrete implementation of the `MetricsEngine` interface. The class uses `AtomicLong` primitives for counters and CAS loops for running min/max, a `CopyOnWriteArrayList` for the latency buffer, and computes all derived statistics (avg, percentiles, RPS) lazily at `snapshot()` time. Four test classes cover unit correctness, interface compliance, property-based invariants, and end-to-end integration with `DefaultVirtualUserEngine`.

## Tasks

- [x] 1. Implement `DefaultMetricsEngine` in `com.latencylab.metrics`
  - [x] 1.1 Create `DefaultMetricsEngine.java` with class declaration and internal fields
    - Declare `public class DefaultMetricsEngine implements MetricsEngine` in package `com.latencylab.metrics`
    - Add `private static final Logger log` via `LoggerFactory.getLogger(DefaultMetricsEngine.class)`
    - Declare all internal fields: `totalCounter`, `successCounter`, `failureCounter`, `runningSum` (all `AtomicLong` initialized to `0`); `runningMin` (`AtomicLong` initialized to `Long.MAX_VALUE`); `runningMax` (`AtomicLong` initialized to `0`); `latencyBuffer` (`CopyOnWriteArrayList<Long>`); `startTimestamp` (`long`)
    - Implement the public no-argument constructor: capture `startTimestamp = System.nanoTime()` and log at DEBUG: `"DefaultMetricsEngine initialized, startTimestamp={}"`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 1.2 Implement `record(long latencyNanos, boolean success)`
    - Guard: if `latencyNanos < 0` throw `IllegalArgumentException` with message containing `"latencyNanos"` and the received value; no state must be modified before this guard
    - Atomically increment `totalCounter` via `incrementAndGet()`
    - Conditionally increment `successCounter` (if `success == true`) or `failureCounter` (if `success == false`) via `incrementAndGet()`
    - Add `latencyNanos` to `runningSum` via `addAndGet(latencyNanos)`
    - Update `runningMin` with a CAS loop: `while (latencyNanos < runningMin.get()) { if (runningMin.compareAndSet(current, latencyNanos)) break; }`
    - Update `runningMax` with a CAS loop: `while (latencyNanos > runningMax.get()) { if (runningMax.compareAndSet(current, latencyNanos)) break; }`
    - Append `latencyNanos` to `latencyBuffer` via `latencyBuffer.add(latencyNanos)`
    - Log at DEBUG: `"record: latencyNanos={}, success={}"`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10_

  - [x] 1.3 Implement `snapshot()`
    - Capture `snapshotTimestamp = System.nanoTime()`
    - Read all atomic values: `total`, `success`, `failure`, `sum`
    - Zero-state short-circuit: if `total == 0` return `new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, snapshotTimestamp)`
    - Compute `avg = sum / total` (integer division)
    - Read `min = runningMin.get()`, `max = runningMax.get()`
    - Copy buffer to `long[] arr`; call `Arrays.sort(arr)`; set `n = arr.length`
    - Compute `p50 = arr[(int) Math.floor(0.50 * n)]`, `p95 = arr[(int) Math.floor(0.95 * n)]`, `p99 = arr[(int) Math.floor(0.99 * n)]`
    - Compute `elapsed = (System.nanoTime() - startTimestamp) / 1_000_000_000.0`; set `rps = (elapsed > 0.0) ? (double) total / elapsed : 0.0`
    - Log at DEBUG: `"snapshot: totalRequests={}, requestsPerSecond={}"`
    - Return `new MetricsSnapshot(total, success, failure, avg, min, max, p50, p95, p99, rps, snapshotTimestamp)`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 4.4, 5.1, 6.1, 6.3, 6.4, 6.5_

- [x] 2. Write `DefaultMetricsEngineTest` (unit tests — required)
  - [x] 2.1 Implement all required unit test methods in `com.latencylab.metrics.DefaultMetricsEngineTest`
    - `testZeroStateSnapshot` — fresh engine returns all-zero `MetricsSnapshot` fields (Req 3.6)
    - `testSingleRecord` — after `record(1000L, true)`, all latency fields equal `1000`, `totalRequests == 1`, `successfulRequests == 1` (Req 3.5)
    - `testRecordIncrementsTotalRequests` — each `record()` call increments `totalRequests` by 1 (Req 2.1, 8.3a)
    - `testSuccessFailureCounting` — `record(x, true)` increments `successfulRequests`; `record(x, false)` increments `failedRequests` (Req 2.2, 8.3b)
    - `testCounterSumInvariant` — after mixed success/failure records, `totalRequests == successfulRequests + failedRequests` (Req 5.2, 8.3f)
    - `testNegativeLatencyThrows` — `record(-1L, true)` throws `IllegalArgumentException` (Req 2.9, 8.3e)
    - `testZeroLatencyAccepted` — `record(0L, true)` does not throw; `minLatencyNanos == 0` (Req 2.7)
    - `testLongMaxValueAccepted` — `record(Long.MAX_VALUE, true)` does not throw; `maxLatencyNanos == Long.MAX_VALUE` (Req 2.8)
    - `testSnapshotTimestampIsRecent` — `snapshotTimestamp` is between `nanoTime()` calls bracketing `snapshot()` (Req 6.1)
    - `testThreadSafety50VirtualThreads` — 50 virtual threads each call `record(1000L, true)` once; assert `totalRequests == 50` and `successfulRequests == 50` (Req 8.4)
    - `testSnapshotDoesNotResetState` — two consecutive `snapshot()` calls return equal statistical fields (Req 6.5)
    - _Requirements: 2.1, 2.2, 2.7, 2.8, 2.9, 3.5, 3.6, 5.2, 6.1, 6.5, 8.3, 8.4_

- [x] 3. Write `DefaultMetricsEngineComplianceTest` (compliance tests — required)
  - [x] 3.1 Implement all compliance test methods in `com.latencylab.metrics.DefaultMetricsEngineComplianceTest`
    - `testImplementsMetricsEngine` — assert `MetricsEngine.class.isAssignableFrom(DefaultMetricsEngine.class)` returns `true` (Req 1.1, 8.2)
    - `testSnapshotDoesNotResetState` — two consecutive `snapshot()` calls with no intervening `record()` return equal `totalRequests`, `avgLatencyNanos`, `minLatencyNanos`, `maxLatencyNanos`, `p50LatencyNanos`, `p95LatencyNanos`, `p99LatencyNanos` (Req 6.2, 6.5)
    - `testRpsIsNonNegative` — `requestsPerSecond >= 0.0` after zero records, after one record, and after multiple records (Req 4.5)
    - `testAvgIsIntegerDivision` — record a known sequence (e.g., `[100L, 200L, 300L]`); assert `avgLatencyNanos == 200` (integer division of sum/count) (Req 3.1)
    - _Requirements: 1.1, 3.1, 4.5, 6.2, 6.5, 8.2, 8.5_

- [x] 4. Checkpoint — Ensure required tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Write `DefaultMetricsEnginePropertyTest` (jqwik property-based tests — recommended)
  - [x]* 5.1 Write property test for Property 1: Counter consistency invariant
    - **Property 1: Counter consistency invariant — totalRequests == successfulRequests + failedRequests**
    - For any sequence of N `record(latencyNanos, success)` calls with arbitrary non-negative `latencyNanos` and arbitrary `success` flags, assert `snapshot().totalRequests == N`, `snapshot().successfulRequests == trueCount`, `snapshot().failedRequests == falseCount`, and `successfulRequests + failedRequests == totalRequests`
    - Use `@Property(tries = 100)` and `@ForAll @Size(min=1, max=50) List<Long>` for latency values and a parallel list of booleans for success flags
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 1: Counter consistency invariant`
    - **Validates: Requirements 2.1, 2.2, 5.1, 5.2, 5.3**

  - [x]* 5.2 Write property test for Property 2: Latency ordering invariant
    - **Property 2: Latency ordering invariant — min ≤ avg ≤ max and P50 ≤ P95 ≤ P99**
    - For any non-empty list of non-negative latency values, assert `minLatencyNanos <= avgLatencyNanos`, `avgLatencyNanos <= maxLatencyNanos`, `p50LatencyNanos <= p95LatencyNanos`, `p95LatencyNanos <= p99LatencyNanos`
    - Use `@Property(tries = 100)` and `@ForAll @NotEmpty List<@LongRange(min=0, max=Long.MAX_VALUE/2) Long>`
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 2: Latency ordering invariant`
    - **Validates: Requirements 3.7, 3.8**

  - [x]* 5.3 Write property test for Property 3: Percentile index formula correctness
    - **Property 3: Percentile index formula correctness**
    - For any sorted `long[]` of length N ≥ 1, assert that `array[(int) Math.floor(R * N)]` for R ∈ {0.50, 0.95, 0.99} produces an index in [0, N-1] and a value ≥ `array[0]` and ≤ `array[N-1]`
    - Use `@Property(tries = 100)` and `@ForAll @Size(min=1, max=200) long[]`; sort the array in the test body before computing indices
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 3: Percentile index formula correctness`
    - **Validates: Requirement 3.4**

  - [x]* 5.4 Write property test for Property 4: Thread-safe concurrent recording — no lost updates
    - **Property 4: Thread-safe concurrent recording — no lost updates**
    - For any `threadCount` in [2, 50] and `recordsPerThread` in [1, 100], spawn `threadCount` virtual threads each calling `record(1000L, true)` exactly `recordsPerThread` times; after all threads join, assert `snapshot().totalRequests == threadCount * recordsPerThread` and `snapshot().successfulRequests == threadCount * recordsPerThread`
    - Use `@Property(tries = 100)`, `@ForAll @IntRange(min=2, max=50) int threadCount`, `@ForAll @IntRange(min=1, max=100) int recordsPerThread`
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 4: Thread-safe concurrent recording`
    - **Validates: Requirements 2.1, 2.3, 7.1**

  - [x]* 5.5 Write property test for Property 5: RPS is non-negative and finite
    - **Property 5: RPS is non-negative and finite for all valid inputs**
    - For any number of `record` calls (including zero), assert `requestsPerSecond >= 0.0`, `!Double.isNaN(rps)`, `!Double.isInfinite(rps)`
    - Use `@Property(tries = 100)` and `@ForAll @IntRange(min=0, max=200) int recordCount`
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 5: RPS is non-negative and finite`
    - **Validates: Requirements 4.3, 4.5**

  - [x]* 5.6 Write property test for Property 6: Snapshot idempotency
    - **Property 6: Snapshot idempotency — repeated snapshots without new records return equivalent data**
    - For any non-empty sequence of `record` calls, call `snapshot()` twice with no intervening `record()`; assert all statistical fields (excluding `snapshotTimestamp` and `requestsPerSecond`) are equal between the two snapshots
    - Use `@Property(tries = 100)` and `@ForAll @NotEmpty List<@LongRange(min=0) Long>`
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 6: Snapshot idempotency`
    - **Validates: Requirements 6.2, 6.5**

  - [x]* 5.7 Write property test for Property 7: Min and max correctness — exact values
    - **Property 7: Min and max correctness — exact values from recorded set**
    - For any non-empty list of non-negative latency values L, assert `snapshot().minLatencyNanos == Collections.min(L)` and `snapshot().maxLatencyNanos == Collections.max(L)`
    - Use `@Property(tries = 100)` and `@ForAll @NotEmpty List<@LongRange(min=0) Long>`
    - Tag comment: `// Feature: latencylab-phase4-metrics-aggregation, Property 7: Min and max correctness`
    - **Validates: Requirements 3.2, 3.3**

- [ ] 6. Write `DefaultMetricsEngineIntegrationTest` (integration tests — recommended)
  - [ ]* 6.1 Implement `testEndToEndNxKRequests`
    - Create a stub `HttpTransportLayer` (anonymous class) that returns `new HttpResponseResult(200, null, 1000L)` for every call
    - Construct `DefaultMetricsEngine` and `DefaultVirtualUserEngine(stubTransport, metricsEngine)`
    - Call `initialize(scenario, N)` then `execute(users, scenario)` with N users and K steps
    - Assert `metricsEngine.snapshot().totalRequests == N * K`
    - _Requirements: 7.1, 7.5_

  - [ ]* 6.2 Implement `testMixedStatusCodeCounting`
    - Create a stub transport that alternates between returning `HttpResponseResult(200, null, 500L)` and `HttpResponseResult(500, null, 500L)`
    - Run with enough users/steps to produce a known mix; assert `successfulRequests` equals the count of 2xx responses and `failedRequests` equals the count of non-2xx responses
    - _Requirements: 7.3_

  - [ ]* 6.3 Implement `testMinMaxFromTransportResults`
    - Create a stub transport that returns results with distinct `latencyNanos` values (e.g., `[100L, 5000L, 300L]` cycling)
    - After `execute` completes, assert `metricsEngine.snapshot().minLatencyNanos == 100L` and `metricsEngine.snapshot().maxLatencyNanos == 5000L`
    - _Requirements: 7.4_

- [ ] 7. Final checkpoint — Verify `mvn verify` passes
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (recommended) and can be skipped for a faster MVP; `DefaultMetricsEngineTest` and `DefaultMetricsEngineComplianceTest` are required by Requirement 8.5
- `DefaultMetricsEngine` is the only new production class in Phase 4 — `MetricsEngine`, `MetricsSnapshot`, and `DefaultVirtualUserEngine` are all unchanged
- The `CopyOnWriteArrayList<Long>` autoboxes `long` to `Long`; the `snapshot()` copy step must unbox to `long[]` before calling `Arrays.sort`
- The zero-state short-circuit in `snapshot()` must return `0` for `minLatencyNanos` (not `Long.MAX_VALUE`, which is the sentinel stored in `runningMin`)
- jqwik `@LongRange` uses `min`/`max` as `long` literals; avoid `Long.MAX_VALUE` as the upper bound in generators to prevent overflow in sum accumulation during property tests
- Integration tests use anonymous-class stubs — no Mockito required; `HttpTransportLayer` is a single-method interface (`execute(RequestStep)`)
- `failIfNoTests=true` is enforced in Maven Surefire; every test class must contain at least one `@Test` or `@Property` method

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3"] },
    { "id": 3, "tasks": ["2.1", "3.1"] },
    { "id": 4, "tasks": ["5.1", "5.2", "5.3", "5.4", "5.5", "5.6", "5.7", "6.1", "6.2", "6.3"] }
  ]
}
```
