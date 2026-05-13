# Requirements Document

## Introduction

Phase 2 of LatencyLab implements the HTTP Execution Engine — the concrete runtime layer that turns the Phase 1 interface contracts into working code. This phase delivers two concrete implementations: `OkHttpTransportLayer` (implementing `HttpTransportLayer`) and `DefaultVirtualUserEngine` (implementing `VirtualUserEngine`). Together they form the core execution path: a virtual user picks up a `Scenario`, iterates through its `RequestStep` list, fires each HTTP request via OkHttp, captures the response and latency, and returns structured `HttpResponseResult` values. Java 21 virtual threads (Project Loom) are used for concurrency. No metrics aggregation or reporting is implemented in this phase — those arrive in Phases 4 and 5.

## Glossary

- **OkHttpTransportLayer**: The concrete class in `com.latencylab.transport` that implements `HttpTransportLayer` using OkHttp as the underlying HTTP client.
- **DefaultVirtualUserEngine**: The concrete class in `com.latencylab.engine` that implements `VirtualUserEngine`, driving virtual users through scenario steps sequentially using Java virtual threads.
- **HttpTransportLayer**: The Phase 1 interface in `com.latencylab.transport` with a single method `execute(RequestStep step): HttpResponseResult`.
- **VirtualUserEngine**: The Phase 1 interface in `com.latencylab.engine` with methods `initialize(Scenario scenario, int userCount): List<VirtualUser>` and `execute(List<VirtualUser> users, Scenario scenario): void`.
- **OkHttpClient**: The OkHttp library's central HTTP client object, responsible for connection pooling, dispatcher configuration, and request dispatch.
- **ConnectionPool**: The OkHttp `ConnectionPool` object that manages reuse of HTTP/1.1 and HTTP/2 connections across requests.
- **RequestStep**: The Phase 1 record in `com.latencylab.model` representing a single HTTP operation, containing `method`, `endpoint`, `body`, `headers`, and `timeoutMillis`.
- **Scenario**: The Phase 1 record in `com.latencylab.model` representing an ordered list of `RequestStep` objects plus test-level configuration (`testName`, `rampUpSeconds`, `durationSeconds`, `userCount`).
- **HttpResponseResult**: The Phase 1 record in `com.latencylab.transport` containing `statusCode` (int), `responseBody` (String, nullable), and `latencyNanos` (long).
- **VirtualUser**: The Phase 1 record in `com.latencylab.model` representing a simulated user, with `userId`, `state` (`VirtualUserState`), `activeScenario`, and `metricsSnapshot`.
- **VirtualUserState**: The Phase 1 enum in `com.latencylab.model` with values `IDLE`, `RUNNING`, `COMPLETED`, `FAILED`.
- **HttpMethod**: The Phase 1 enum in `com.latencylab.model` with values `GET`, `POST`, `PUT`, `PATCH`, `DELETE`.
- **Virtual_Thread**: A Java 21 Project Loom lightweight thread created via `Thread.ofVirtual().start(...)` or `Executors.newVirtualThreadPerTaskExecutor()`.
- **Latency_Measurement**: The elapsed wall-clock time for a single HTTP request, measured in nanoseconds using `System.nanoTime()` deltas — never `System.currentTimeMillis()`.
- **baseUrl**: A runtime-supplied base URL string (e.g., `https://api.example.com`) that is prepended to each `RequestStep.endpoint` to form the full request URL.

---

## Requirements

### Requirement 1: OkHttpTransportLayer — Concrete HTTP Transport Implementation

**User Story:** As a developer, I want a concrete `HttpTransportLayer` implementation backed by OkHttp, so that `RequestStep` objects can be executed as real HTTP requests and their results captured as `HttpResponseResult` values.

#### Acceptance Criteria

1. THE `OkHttpTransportLayer` SHALL be a class in `com.latencylab.transport` that declares `implements HttpTransportLayer`.
2. THE `OkHttpTransportLayer` SHALL accept a `baseUrl` (String) at construction time and, WHEN forming the full request URL, SHALL concatenate `baseUrl` and `RequestStep.endpoint` such that exactly one `/` separates them — stripping any trailing `/` from `baseUrl` and any leading `/` from `endpoint` before joining — to produce the full request URL.
3. WHEN `execute(RequestStep step)` is called with a `RequestStep` whose `method` is `GET`, THE `OkHttpTransportLayer` SHALL issue an HTTP GET request to the constructed URL with no request body.
4. WHEN `execute(RequestStep step)` is called with a `RequestStep` whose `method` is `POST`, `PUT`, or `PATCH`, THE `OkHttpTransportLayer` SHALL issue the corresponding HTTP request with the `RequestStep.body` as the request body; IF `RequestStep.body` is null, THE `OkHttpTransportLayer` SHALL send an empty body.
5. WHEN `execute(RequestStep step)` is called with a `RequestStep` whose `method` is `DELETE`, THE `OkHttpTransportLayer` SHALL issue an HTTP DELETE request; IF `RequestStep.body` is non-null, THE `OkHttpTransportLayer` SHALL include it as the request body.
6. WHEN `execute(RequestStep step)` is called, THE `OkHttpTransportLayer` SHALL apply all entries from `RequestStep.headers` as HTTP request headers.
7. WHEN `execute(RequestStep step)` is called, THE `OkHttpTransportLayer` SHALL apply `RequestStep.timeoutMillis` as the per-request read timeout for that request.
8. WHEN `execute(RequestStep step)` is called and the HTTP response is received, THE `OkHttpTransportLayer` SHALL return an `HttpResponseResult` containing: the HTTP status code (a standard HTTP status code in the range 100–599), the response body as a UTF-8 string (null if the OkHttp `ResponseBody` is absent or has 0 bytes), and the `latencyNanos` measured from immediately before the OkHttp `newCall(request).execute()` call to immediately after it returns, using `System.nanoTime()` deltas.
9. IF `execute(RequestStep step)` encounters a network-level failure (e.g., connection refused, timeout, DNS failure), THE `OkHttpTransportLayer` SHALL return an `HttpResponseResult` with `statusCode` of `0`, `responseBody` of `null`, and `latencyNanos` reflecting the elapsed time up to the point of failure.
10. WHEN `execute(RequestStep step)` dispatches a request, THE `OkHttpTransportLayer` SHALL log at DEBUG level a message containing `step.name`, the HTTP method, and the full constructed URL; WHEN the response is received, THE `OkHttpTransportLayer` SHALL log at DEBUG level a message containing `step.name`, the HTTP status code, and the `latencyNanos` value.
11. IF `execute(RequestStep step)` is called with a null `RequestStep`, THE `OkHttpTransportLayer` SHALL throw a `NullPointerException`.

---

### Requirement 2: OkHttp Client Configuration and Connection Pooling

**User Story:** As a developer, I want the OkHttp client to be properly configured with a shared connection pool and sensible defaults, so that connections are reused across requests and the client is not re-created on every call.

#### Acceptance Criteria

1. THE `OkHttpTransportLayer` SHALL construct and hold a single `OkHttpClient` instance for its lifetime.
2. THE `OkHttpTransportLayer` SHALL NOT create a new `OkHttpClient` per `execute` call.
3. THE `OkHttpTransportLayer` SHALL configure its `OkHttpClient` with a `ConnectionPool` that allows a maximum of 200 idle connections with a keep-alive duration of 5 minutes.
4. THE `OkHttpTransportLayer` SHALL configure the `OkHttpClient` with a default connect timeout of 10 seconds.
5. WHEN `execute(RequestStep step)` is called and `RequestStep.timeoutMillis` is greater than 0, THE `OkHttpTransportLayer` SHALL apply that value as the per-request read timeout, overriding the client-level default for that call only.
6. THE `OkHttpTransportLayer` SHALL configure the `OkHttpClient` with a default read timeout of 30 seconds, used for any request where `RequestStep.timeoutMillis` is 0 or not set.
7. THE `OkHttpTransportLayer` SHALL configure the `OkHttpClient` with a default write timeout of 10 seconds.
8. THE `OkHttpTransportLayer` SHALL implement `java.io.Closeable`.
9. WHEN `close()` is explicitly called on `OkHttpTransportLayer`, THE `OkHttpTransportLayer` SHALL invoke `connectionPool().evictAll()` and `dispatcher().executorService().shutdown()` on the held `OkHttpClient` to release all held resources.
10. THE `OkHttpTransportLayer` SHALL NOT shut down the `OkHttpClient`'s connection pool or dispatcher unless `close()` has been explicitly invoked.
11. WHEN `close()` is called more than once on the same `OkHttpTransportLayer` instance, THE `OkHttpTransportLayer` SHALL complete without throwing an exception (idempotent close).
12. WHEN `execute(RequestStep step)` is called after `close()` has been invoked, THE `OkHttpTransportLayer` SHALL throw an `IllegalStateException`.
13. WHEN `OkHttpTransportLayer` is constructed with a null `baseUrl`, THE `OkHttpTransportLayer` SHALL throw an `IllegalArgumentException` whose message contains the word `"baseUrl"`.
14. WHEN `OkHttpTransportLayer` is constructed with a blank (whitespace-only) `baseUrl`, THE `OkHttpTransportLayer` SHALL throw an `IllegalArgumentException` whose message contains the word `"baseUrl"`.

---

### Requirement 3: DefaultVirtualUserEngine — Virtual User Initialization

**User Story:** As a developer, I want a concrete `VirtualUserEngine` implementation that can initialize a pool of virtual users from a scenario, so that each user is ready to execute the scenario independently.

#### Acceptance Criteria

1. THE `DefaultVirtualUserEngine` SHALL be a class in `com.latencylab.engine` that declares `implements VirtualUserEngine`.
2. WHEN `initialize(Scenario scenario, int userCount)` is called, THE `DefaultVirtualUserEngine` SHALL return an unmodifiable `List<VirtualUser>` of exactly `userCount` elements.
3. WHEN `initialize(Scenario scenario, int userCount)` is called, the element at index `i` (0-based) in the returned list SHALL have: `userId` equal to `"user-" + (i + 1)`, `state` set to `VirtualUserState.IDLE`, `activeScenario` set to the provided `Scenario` (the `int userCount` parameter is used as the pool size independently of `Scenario.userCount`), and `metricsSnapshot` set to `null`; this rule SHALL hold for every index from 0 to `userCount - 1` inclusive.
4. WHEN `initialize(Scenario scenario, int userCount)` is called with `userCount` less than 1, THE `DefaultVirtualUserEngine` SHALL throw an `IllegalArgumentException`.
5. WHEN `initialize(Scenario scenario, int userCount)` is called with a null `Scenario`, THE `DefaultVirtualUserEngine` SHALL throw a `NullPointerException`.

---

### Requirement 4: DefaultVirtualUserEngine — Scenario Execution

**User Story:** As a developer, I want the `DefaultVirtualUserEngine` to drive each virtual user through all steps of a scenario concurrently using Java virtual threads, so that the engine can simulate realistic concurrent load.

#### Acceptance Criteria

1. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called, THE `DefaultVirtualUserEngine` SHALL launch one Java Virtual_Thread per `VirtualUser` in the `users` list.
2. WHEN a Virtual_Thread is launched for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL execute each `RequestStep` in `scenario.steps()` sequentially within that thread, in list order.
3. WHEN executing a `RequestStep` for a `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL delegate to `HttpTransportLayer.execute(step)` and store the returned `HttpResponseResult` for that step; the result SHALL NOT be aggregated into any metrics structure in this phase.
4. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called, THE `DefaultVirtualUserEngine` SHALL block until all Virtual_Threads have completed — where "completed" means each thread has either finished all steps or terminated due to an unhandled exception as defined in criterion 7.
5. WHEN a `VirtualUser` begins executing its steps, THE `DefaultVirtualUserEngine` SHALL log at DEBUG level a message containing the `userId` and the total number of steps to be executed.
6. WHEN a `VirtualUser` completes all steps without error, THE `DefaultVirtualUserEngine` SHALL log at DEBUG level a message containing the `userId` and a completion indicator.
7. IF `HttpTransportLayer.execute(step)` throws an exception for a given `VirtualUser`, THE `DefaultVirtualUserEngine` SHALL log the exception at ERROR level including the `userId` and the `step.name`; SHALL skip all remaining steps for that `VirtualUser`; and SHALL NOT propagate the exception to other virtual users' threads or cause `execute` to throw.
8. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called with a null `users` list or a null `Scenario`, THE `DefaultVirtualUserEngine` SHALL throw a `NullPointerException`.
9. WHEN `execute(List<VirtualUser> users, Scenario scenario)` is called with an empty `users` list, THE `DefaultVirtualUserEngine` SHALL return immediately without launching any threads.
10. WHEN a `VirtualUser`'s Virtual_Thread begins executing steps, THE `DefaultVirtualUserEngine` SHALL transition that `VirtualUser`'s `state` to `VirtualUserState.RUNNING`; WHEN all steps complete without error, SHALL transition `state` to `VirtualUserState.COMPLETED`; IF an exception terminates the thread early per criterion 7, SHALL transition `state` to `VirtualUserState.FAILED`.

---

### Requirement 5: Latency Measurement Correctness

**User Story:** As a developer, I want latency to be measured precisely around the actual HTTP call, so that transport overhead is captured accurately and consistently.

#### Acceptance Criteria

1. WHEN `execute(RequestStep step)` is called, THE `OkHttpTransportLayer` SHALL record the start timestamp using `System.nanoTime()` with no intervening statements between the `nanoTime()` call and the `OkHttpClient.newCall(request).execute()` invocation.
2. WHEN `execute(RequestStep step)` is called, THE `OkHttpTransportLayer` SHALL record the end timestamp using `System.nanoTime()` with no intervening statements between the return (or throw) of `OkHttpClient.newCall(request).execute()` and the `nanoTime()` call.
3. THE `HttpResponseResult.latencyNanos` field SHALL be set to `endNanos - startNanos`, where `startNanos` and `endNanos` are the values obtained from the `System.nanoTime()` calls defined in criteria 1 and 2.
4. THE `OkHttpTransportLayer` SHALL NOT use `System.currentTimeMillis()` for latency measurement at any point.
5. THE `latencyNanos` field of every `HttpResponseResult` returned by `OkHttpTransportLayer.execute(RequestStep)` SHALL be greater than or equal to 0.

---

### Requirement 6: HTTP Method and Body Mapping

**User Story:** As a developer, I want all five HTTP methods to be correctly mapped to OkHttp request objects, so that the transport layer faithfully represents the intent of each `RequestStep`.

#### Acceptance Criteria

1. THE `OkHttpTransportLayer` SHALL map `HttpMethod.GET` to an OkHttp `Request` with method `"GET"` and no request body.
2. THE `OkHttpTransportLayer` SHALL map `HttpMethod.POST` to an OkHttp `Request` with method `"POST"` and a `RequestBody` constructed from `RequestStep.body` encoded as UTF-8; IF `body` is null, THE `OkHttpTransportLayer` SHALL use a zero-byte `RequestBody` with media type `application/json; charset=utf-8`.
3. THE `OkHttpTransportLayer` SHALL map `HttpMethod.PUT` to an OkHttp `Request` with method `"PUT"` and a `RequestBody` constructed from `RequestStep.body` encoded as UTF-8; IF `body` is null, THE `OkHttpTransportLayer` SHALL use a zero-byte `RequestBody` with media type `application/json; charset=utf-8`.
4. THE `OkHttpTransportLayer` SHALL map `HttpMethod.PATCH` to an OkHttp `Request` with method `"PATCH"` and a `RequestBody` constructed from `RequestStep.body` encoded as UTF-8; IF `body` is null, THE `OkHttpTransportLayer` SHALL use a zero-byte `RequestBody` with media type `application/json; charset=utf-8`.
5. THE `OkHttpTransportLayer` SHALL map `HttpMethod.DELETE` to an OkHttp `Request` with method `"DELETE"`; IF `RequestStep.body` is non-null, THE `OkHttpTransportLayer` SHALL include it as the request body encoded as UTF-8; IF `body` is null, THE `OkHttpTransportLayer` SHALL send no request body.
6. WHEN `RequestStep.body` is non-null and has length greater than 0, THE `OkHttpTransportLayer` SHALL set the `Content-Type` header to `application/json; charset=utf-8` unless `RequestStep.headers` already contains a `Content-Type` entry, in which case THE `OkHttpTransportLayer` SHALL use the value from `RequestStep.headers`; WHEN `RequestStep.body` is null or has length 0, THE `OkHttpTransportLayer` SHALL NOT inject a `Content-Type` header unless `RequestStep.headers` already contains one.

---

### Requirement 7: Build and Interface Compliance

**User Story:** As a developer, I want the Phase 2 implementations to be verifiable by the existing build and test infrastructure, so that the project remains in a `BUILD SUCCESS` state after Phase 2 is merged.

#### Acceptance Criteria

1. WHEN `mvn verify` is executed on the Phase 2 codebase, THE Maven_Build SHALL complete with `BUILD SUCCESS`.
2. THE `OkHttpTransportLayer` class SHALL satisfy `HttpTransportLayer.class.isAssignableFrom(OkHttpTransportLayer.class)` returning `true`, verifiable by a JUnit 5 reflection test located in `com.latencylab.transport` under `src/test/java`.
3. THE `DefaultVirtualUserEngine` class SHALL satisfy `VirtualUserEngine.class.isAssignableFrom(DefaultVirtualUserEngine.class)` returning `true`, verifiable by a JUnit 5 reflection test located in `com.latencylab.engine` under `src/test/java`.
4. THE Maven_Build SHALL include JUnit 5 unit tests for `OkHttpTransportLayer` in `com.latencylab.transport` that assert: for each of the five `HttpMethod` values (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`), the returned `HttpResponseResult.statusCode` matches the stub server's response code, `responseBody` contains the expected UTF-8 string or is null for empty bodies, and `latencyNanos` is greater than or equal to 1; and that a network failure returns `statusCode == 0`, `responseBody == null`, and `latencyNanos >= 0`; THE Maven_Build SHALL fail if these tests are absent, enforced via `failIfNoTests=true` in Maven Surefire.
5. THE Maven_Build SHALL include JUnit 5 unit tests for `DefaultVirtualUserEngine` in `com.latencylab.engine` that assert: `initialize` returns a list of exactly `userCount` elements where element at index `i` has `userId == "user-" + (i+1)`, `state == VirtualUserState.IDLE`, `activeScenario` equal to the provided `Scenario`, and `metricsSnapshot == null`; and that `execute` invokes `HttpTransportLayer.execute` exactly once per step per user; THE Maven_Build SHALL fail if these tests are absent, enforced via `failIfNoTests=true` in Maven Surefire.
