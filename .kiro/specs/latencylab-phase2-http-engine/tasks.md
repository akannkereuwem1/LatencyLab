# Implementation Plan: LatencyLab Phase 2 — HTTP Execution Engine

## Overview

Implement the two concrete runtime classes that bring the Phase 1 interface contracts to life: `OkHttpTransportLayer` (HTTP transport backed by OkHttp 4.12.0) and `DefaultVirtualUserEngine` (virtual user orchestration via Java 21 virtual threads). Both classes are covered by JUnit 5 example tests and jqwik property tests. No new `pom.xml` dependencies are required — OkHttp's `MockWebServer` ships with the existing `okhttp` artifact.

## Tasks

- [x] 1. Implement `OkHttpTransportLayer` — constructor and client configuration
  - [x] 1.1 Create `OkHttpTransportLayer.java` in `src/main/java/com/latencylab/transport/`
    - Declare `public class OkHttpTransportLayer implements HttpTransportLayer, java.io.Closeable`
    - Add `private static final Logger log = LoggerFactory.getLogger(OkHttpTransportLayer.class)`
    - Add `private static final MediaType JSON = MediaType.get("application/json; charset=utf-8")`
    - Add `private final String baseUrl` (normalized — trailing slashes stripped) and `private final OkHttpClient client`
    - Add `private volatile boolean closed = false`
    - Constructor: validate `baseUrl` null → `IllegalArgumentException("baseUrl must not be null")`, blank → `IllegalArgumentException("baseUrl must not be blank")`; strip trailing slashes; build single `OkHttpClient` with `ConnectionPool(200, 5, TimeUnit.MINUTES)`, `connectTimeout(10s)`, `readTimeout(30s)`, `writeTimeout(10s)`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 2.7, 2.13, 2.14_

  - [x] 1.2 Implement `buildUrl(String endpoint)` private method
    - Strip leading `/` from `endpoint`; return `baseUrl + "/" + normalizedEndpoint`
    - _Requirements: 1.2_

  - [x] 1.3 Implement `buildBody(String body)` private method
    - `null` body → `RequestBody.create(new byte[0], JSON)`; non-null → `RequestBody.create(body, JSON)`
    - _Requirements: 6.2, 6.3, 6.4_

  - [x] 1.4 Implement `buildRequest(RequestStep step, String url)` private method
    - Create `Request.Builder`, apply all `step.headers()` via `addHeader`
    - Switch on `step.method()`: `GET` → `.get()`; `POST` → `.post(buildBody(step.body()))`; `PUT` → `.put(buildBody(step.body()))`; `PATCH` → `.patch(buildBody(step.body()))`; `DELETE` → body non-null ? `.delete(buildBody(step.body()))` : `.delete()`
    - Inject `Content-Type: application/json; charset=utf-8` only when body is non-null and non-empty AND `step.headers()` does not already contain `Content-Type`
    - _Requirements: 1.3, 1.4, 1.5, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

- [x] 2. Implement `OkHttpTransportLayer` — `execute` and `close`
  - [x] 2.1 Implement `execute(RequestStep step)` method
    - `Objects.requireNonNull(step, "step must not be null")`; if `closed` throw `IllegalStateException`
    - Call `buildUrl`, `buildRequest`; log DEBUG dispatch message with `step.name()`, method, URL
    - Build per-request client via `client.newBuilder().readTimeout(step.timeoutMillis(), MILLISECONDS).build()`
    - Bracket `callClient.newCall(request).execute()` with `System.nanoTime()` — start immediately before, end immediately after return or throw
    - On success: extract status code, read body as UTF-8 (null if absent or zero-length), log DEBUG completion, return `HttpResponseResult(statusCode, body, endNanos - startNanos)`
    - Catch `IOException`: capture `endNanos`, log DEBUG failure, return `HttpResponseResult(0, null, endNanos - startNanos)`
    - _Requirements: 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 2.5, 2.12, 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 2.2 Implement `close()` method
    - If `closed` return immediately (idempotent); set `closed = true`
    - Call `client.connectionPool().evictAll()` then `client.dispatcher().executorService().shutdown()`
    - _Requirements: 2.8, 2.9, 2.10, 2.11_

- [x] 3. Checkpoint — compile transport layer
  - Ensure `mvn compile` exits cleanly with no errors or unchecked-warning failures. Ask the user if questions arise.

- [x] 4. Write `OkHttpTransportLayerTest` — example tests
  - [x] 4.1 Create `OkHttpTransportLayerTest.java` in `src/test/java/com/latencylab/transport/`
    - Start `MockWebServer` in `@BeforeEach`, shut down in `@AfterEach`; construct `OkHttpTransportLayer` pointing at `mockWebServer.url("/").toString()`
    - `getRequest_returnsStatusAndBody`: enqueue 200 with body; assert `statusCode == 200`, `responseBody` matches, `latencyNanos >= 1`
    - `postRequest_withBody_sendsBody`: enqueue 201; assert recorded request has correct body
    - `postRequest_nullBody_sendsEmptyBody`: enqueue 200; assert recorded request body is zero bytes
    - `putRequest_withBody_sendsBody`: enqueue 200; assert method and body
    - `patchRequest_withBody_sendsBody`: enqueue 200; assert method and body
    - `deleteRequest_noBody_sendsNoBody`: enqueue 204; assert no body in recorded request
    - `deleteRequest_withBody_sendsBody`: enqueue 200; assert body present in recorded request
    - `networkFailure_returnsStatusZero`: shut down server before call; assert `statusCode == 0`, `responseBody == null`, `latencyNanos >= 0`
    - `nullStep_throwsNullPointerException`: `execute(null)` → `assertThrows(NullPointerException.class, ...)`
    - `executeAfterClose_throwsIllegalStateException`: `close()` then `execute(step)` → `assertThrows(IllegalStateException.class, ...)`
    - `nullBaseUrl_throwsIllegalArgumentException`: `new OkHttpTransportLayer(null)` → IAE message contains `"baseUrl"`
    - `blankBaseUrl_throwsIllegalArgumentException`: `new OkHttpTransportLayer("   ")` → IAE message contains `"baseUrl"`
    - _Requirements: 1.3, 1.4, 1.5, 1.8, 1.9, 1.11, 2.11, 2.12, 2.13, 2.14, 7.2, 7.4_

- [x] 5. Write `OkHttpTransportLayerTest` — property tests
  - [x]* 5.1 Write property test for URL construction (Property 1)
    - `// Feature: latencylab-phase2-http-engine, Property 1: URL construction produces exactly one separator slash`
    - `@Property(tries = 100)`: generate random alpha `baseUrl` strings (with/without trailing slashes) and `endpoint` strings (with/without leading slashes); construct `OkHttpTransportLayer`, call `buildUrl` via reflection or via a full round-trip through `MockWebServer`; assert the recorded request path has exactly one `/` at the join boundary
    - **Property 1: URL construction produces exactly one separator slash**
    - **Validates: Requirements 1.2**

  - [x]* 5.2 Write property test for body round-trip on POST/PUT/PATCH (Property 2)
    - `// Feature: latencylab-phase2-http-engine, Property 2: Request body round-trip for POST, PUT, and PATCH`
    - `@Property(tries = 100)`: generate random non-null body strings and one of `{POST, PUT, PATCH}`; enqueue 200 on `MockWebServer`; assert recorded request body equals original string
    - **Property 2: Request body round-trip for POST, PUT, and PATCH**
    - **Validates: Requirements 1.4, 6.2, 6.3, 6.4**

  - [x]* 5.3 Write property test for header forwarding (Property 3)
    - `// Feature: latencylab-phase2-http-engine, Property 3: All request headers are forwarded to the server`
    - `@Property(tries = 100)`: generate random `Map<String, String>` of headers; enqueue 200; assert every key-value pair appears in the recorded request headers
    - **Property 3: All request headers are forwarded to the server**
    - **Validates: Requirements 1.6**

  - [x]* 5.4 Write property test for response field preservation (Property 4)
    - `// Feature: latencylab-phase2-http-engine, Property 4: Response fields are preserved in HttpResponseResult`
    - `@Property(tries = 100)`: generate random status codes in [200, 299] (MockWebServer constraint) and random body strings; assert `result.statusCode()` and `result.responseBody()` match served values
    - **Property 4: Response fields are preserved in HttpResponseResult**
    - **Validates: Requirements 1.8**

  - [x]* 5.5 Write property test for non-negative latency (Property 5)
    - `// Feature: latencylab-phase2-http-engine, Property 5: latencyNanos is non-negative for all outcomes`
    - `@Property(tries = 100)`: alternate between successful responses and network failures; assert `result.latencyNanos() >= 0` in all cases
    - **Property 5: latencyNanos is non-negative for all outcomes**
    - **Validates: Requirements 5.3, 5.5**

  - [x]* 5.6 Write property test for blank baseUrl validation (Property 6)
    - `// Feature: latencylab-phase2-http-engine, Property 6: Blank baseUrl always throws IllegalArgumentException`
    - `@Property(tries = 100)`: generate strings composed entirely of `{' ', '\t', '\n'}` with `ofMinLength(1)`; assert `new OkHttpTransportLayer(whitespace)` throws `IllegalArgumentException` with message containing `"baseUrl"`
    - **Property 6: Blank baseUrl always throws IllegalArgumentException**
    - **Validates: Requirements 2.14**

  - [x] 5.7 Write property test for close idempotency (Property 7)
    - `// Feature: latencylab-phase2-http-engine, Property 7: close() is idempotent`
    - `@Property(tries = 100)`: generate random call count N in [2, 10]; call `close()` N times on the same instance; assert no exception is thrown on any call after the first
    - **Property 7: close() is idempotent**
    - **Validates: Requirements 2.11**

- [x] 6. Checkpoint — transport tests pass
  - Run `mvn test -pl . -Dtest=OkHttpTransportLayerTest` and confirm all example and property tests pass. Ask the user if questions arise.

- [x] 7. Implement `DefaultVirtualUserEngine`
  - [x] 7.1 Create `DefaultVirtualUserEngine.java` in `src/main/java/com/latencylab/engine/`
    - Declare `public class DefaultVirtualUserEngine implements VirtualUserEngine`
    - Add `private static final Logger log = LoggerFactory.getLogger(DefaultVirtualUserEngine.class)`
    - Constructor: `Objects.requireNonNull(transport, "transport must not be null")`; store as `private final HttpTransportLayer transport`
    - _Requirements: 3.1_

  - [x] 7.2 Implement `initialize(Scenario scenario, int userCount)` method
    - `Objects.requireNonNull(scenario, "scenario must not be null")`; `userCount < 1` → `IllegalArgumentException`
    - Build `ArrayList<VirtualUser>` of size `userCount`; element at index `i` has `userId = "user-" + (i+1)`, `state = VirtualUserState.IDLE`, `activeScenario = scenario`, `metricsSnapshot = null`
    - Return `Collections.unmodifiableList(users)`
    - _Requirements: 3.2, 3.3, 3.4, 3.5_

  - [x] 7.3 Implement `runUser(VirtualUser user, Scenario scenario)` private method
    - Create new `VirtualUser` record with `state = RUNNING`; log DEBUG start message with `userId` and step count
    - Iterate `scenario.steps()` sequentially; call `transport.execute(step)` for each; store result locally (not aggregated)
    - On success: create new `VirtualUser` record with `state = COMPLETED`; log DEBUG completion message
    - Catch `Exception e`: create new `VirtualUser` record with `state = FAILED`; log ERROR with `userId`, current `step.name()`, and exception; break — do not propagate
    - _Requirements: 4.2, 4.3, 4.5, 4.6, 4.7, 4.10_

  - [x] 7.4 Implement `execute(List<VirtualUser> users, Scenario scenario)` method
    - `Objects.requireNonNull(users, ...)` and `Objects.requireNonNull(scenario, ...)`; empty list → return immediately
    - For each user: `Thread.ofVirtual().name("vuser-" + user.userId()).start(() -> runUser(user, scenario))`; collect all threads
    - Join all threads; on `InterruptedException` re-interrupt current thread and break
    - _Requirements: 4.1, 4.4, 4.8, 4.9_

- [x] 8. Write `DefaultVirtualUserEngineTest` — example tests
  - [x] 8.1 Create `DefaultVirtualUserEngineTest.java` in `src/test/java/com/latencylab/engine/`
    - Define inner class `CountingTransport implements HttpTransportLayer`: records invocation count per step name; returns `HttpResponseResult(200, "ok", 1000L)`
    - Define inner class `FailingTransport implements HttpTransportLayer`: throws `RuntimeException("simulated failure")` on every call
    - `initialize_returnsCorrectListSize`: `initialize(scenario, 5)` → list size 5
    - `initialize_elementFields_correct`: each element has `userId == "user-" + (i+1)`, `state == IDLE`, `activeScenario == scenario`, `metricsSnapshot == null`
    - `initialize_listIsUnmodifiable`: `list.add(...)` → `UnsupportedOperationException`
    - `initialize_userCountZero_throwsIAE`: `initialize(scenario, 0)` → `IllegalArgumentException`
    - `initialize_userCountNegative_throwsIAE`: `initialize(scenario, -1)` → `IllegalArgumentException`
    - `initialize_nullScenario_throwsNPE`: `initialize(null, 5)` → `NullPointerException`
    - `execute_invokesTransportOncePerStepPerUser`: 3 users × 2 steps → `CountingTransport` called 6 times total
    - `execute_emptyUsers_returnsImmediately`: `execute(emptyList, scenario)` → returns without error
    - `execute_nullUsers_throwsNPE`: `execute(null, scenario)` → `NullPointerException`
    - `execute_nullScenario_throwsNPE`: `execute(users, null)` → `NullPointerException`
    - `execute_failingTransport_doesNotPropagateException`: `FailingTransport` → `execute` returns normally
    - `execute_failingTransport_otherUsersComplete`: 1 failing user + 2 normal users → normal users complete all steps (verify via `CountingTransport` counts)
    - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.1, 4.4, 4.7, 4.8, 4.9, 7.3, 7.5_

- [x] 9. Write `DefaultVirtualUserEngineTest` — property tests
  - [ ]* 9.1 Write property test for initialize list structure (Property 8)
    - `// Feature: latencylab-phase2-http-engine, Property 8: initialize returns a correctly structured list for any valid userCount`
    - `@Property(tries = 100)`: generate `userCount` in [1, 1000] and a valid `Scenario`; assert list size equals `userCount`; assert every element at index `i` has `userId == "user-" + (i+1)`, `state == IDLE`, `activeScenario == scenario`, `metricsSnapshot == null`; assert list is unmodifiable
    - **Property 8: initialize returns a correctly structured list for any valid userCount**
    - **Validates: Requirements 3.2, 3.3**

  - [x]* 9.2 Write property test for per-user exception isolation (Property 9)
    - `// Feature: latencylab-phase2-http-engine, Property 9: Per-user exception isolation — failing users do not affect other users`
    - `@Property(tries = 100)`: generate total user count N in [2, 20]; designate exactly one user's transport call to throw; use a `SelectiveFailingTransport` that fails only for a specific `userId`; assert `execute` returns normally and all non-failing users had their steps invoked the expected number of times
    - **Property 9: Per-user exception isolation — failing users do not affect other users**
    - **Validates: Requirements 4.7**

- [x] 10. Write interface compliance reflection tests
  - [ ] 10.1 Add `OkHttpTransportLayerComplianceTest` in `src/test/java/com/latencylab/transport/`
    - Assert `HttpTransportLayer.class.isAssignableFrom(OkHttpTransportLayer.class)` returns `true`
    - Assert `java.io.Closeable.class.isAssignableFrom(OkHttpTransportLayer.class)` returns `true`
    - _Requirements: 7.2_

  - [x] 10.2 Add `DefaultVirtualUserEngineComplianceTest` in `src/test/java/com/latencylab/engine/`
    - Assert `VirtualUserEngine.class.isAssignableFrom(DefaultVirtualUserEngine.class)` returns `true`
    - _Requirements: 7.3_

- [x] 11. Final Checkpoint — full `mvn verify`
  - Run `mvn verify` and confirm `BUILD SUCCESS` with all tests discovered and passing. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- `OkHttpTransportLayer` must be implemented before its tests (tasks 1–2 before tasks 4–5)
- `DefaultVirtualUserEngine` must be implemented before its tests (task 7 before tasks 8–9)
- `MockWebServer` is available from the existing `com.squareup.okhttp3:okhttp` dependency — no new dependency needed
- `VirtualUser` is an immutable record; state transitions in `runUser` create new instances (local to the thread in Phase 2)
- `Thread.join()` throws `InterruptedException` — re-interrupt the calling thread and break the join loop
- Property tests use `@Property(tries = 100)` minimum; each includes a comment with feature name and property number
- No new `pom.xml` dependencies should be added

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["1.4"] },
    { "id": 3, "tasks": ["2.1", "2.2"] },
    { "id": 4, "tasks": ["4.1", "7.1"] },
    { "id": 5, "tasks": ["5.1", "5.2", "5.3", "5.4", "5.5", "5.6", "5.7", "7.2"] },
    { "id": 6, "tasks": ["7.3", "10.1"] },
    { "id": 7, "tasks": ["7.4", "8.1"] },
    { "id": 8, "tasks": ["9.1", "9.2", "10.2"] }
  ]
}
```
