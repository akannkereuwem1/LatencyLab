# Implementation Plan: LatencyLab Phase 1 — Architecture & Project Setup

## Overview

Bootstrap the complete Maven project scaffold for LatencyLab: `pom.xml`, package hierarchy, immutable data model records, component interfaces, CLI entry point skeleton, Logback configuration, and a full JUnit 5 + jqwik test suite. No runtime execution logic is implemented — the deliverable is a compilable, testable scaffold that exits `mvn verify` with `BUILD SUCCESS`.

## Tasks

- [x] 1. Create `pom.xml` with all dependencies and plugin configuration
  - Define `groupId`, `artifactId` (`latencylab`), `version` (`0.1.0-SNAPSHOT`), `packaging` (`jar`)
  - Set `<java.version>21</java.version>`, `<maven.compiler.source>21</maven.compiler.source>`, `<maven.compiler.target>21</maven.compiler.target>`
  - Add compile-scope dependencies: `com.squareup.okhttp3:okhttp`, `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`, `org.slf4j:slf4j-api`
  - Add runtime-scope dependency: `ch.qos.logback:logback-classic`
  - Add test-scope dependencies: `org.junit.jupiter:junit-jupiter`, `net.jqwik:jqwik`
  - Configure `maven-compiler-plugin` with `<compilerArgs>`: `-Xlint:unchecked`, `-Xlint:deprecation`
  - Configure `maven-surefire-plugin` with `<failIfNoTests>true</failIfNoTests>`
  - Configure `maven-shade-plugin` for uber-JAR with `Main-Class: com.latencylab.cli.LatencyLabMain` and `Implementation-Version: ${project.version}` in manifest
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 7.3, 7.4, 7.5_

- [x] 2. Create model enums
  - [x] 2.1 Create `VirtualUserState` enum in `com.latencylab.model`
    - Define values: `IDLE`, `RUNNING`, `COMPLETED`, `FAILED`
    - File: `src/main/java/com/latencylab/model/VirtualUserState.java`
    - _Requirements: 3.5_

  - [x] 2.2 Create `HttpMethod` enum in `com.latencylab.model`
    - Define values: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
    - File: `src/main/java/com/latencylab/model/HttpMethod.java`
    - _Requirements: 3.6_

- [x] 3. Create `RequestStep` record
  - [x] 3.1 Implement `RequestStep` record in `com.latencylab.model`
    - Components: `name` (String, non-null), `method` (HttpMethod, non-null), `endpoint` (String, non-null), `body` (String, nullable), `headers` (Map<String, String>, non-null), `timeoutMillis` (int, [1, 300000])
    - Compact constructor: `Objects.requireNonNull` for `name`, `method`, `endpoint`, `headers`; range-check `timeoutMillis`; `headers = Map.copyOf(headers)` for defensive copy
    - File: `src/main/java/com/latencylab/model/RequestStep.java`
    - _Requirements: 3.3_

  - [x]* 3.2 Write property tests for `RequestStep` — Properties 1, 2, 9
    - File: `src/test/java/com/latencylab/model/RequestStepPropertyTest.java`
    - **Property 1: RequestStep construction preserves all field values**
    - **Validates: Requirements 3.3**
    - **Property 2: RequestStep rejects out-of-range timeoutMillis**
    - **Validates: Requirements 3.3**
    - **Property 9: Record immutability — headers map is defensively copied**
    - **Validates: Requirements 3.3**
    - Use `@Property(tries = 100)`, `@ForAll`, `@IntRange`, custom `@Provide` arbitraries for `HttpMethod` enum
    - Tag each `@Property` with comment: `// Feature: latencylab-phase1-setup, Property N: <property_text>`

- [x] 4. Create `Scenario` record
  - [x] 4.1 Implement `Scenario` record in `com.latencylab.model`
    - Components: `testName` (String, non-null), `steps` (List<RequestStep>, non-null, non-empty), `rampUpSeconds` (int, [0, 3600]), `durationSeconds` (int, [1, 86400]), `userCount` (int, [1, 100000])
    - Compact constructor: `Objects.requireNonNull` for `testName`, `steps`; empty-check on `steps`; range-checks for all three int fields; `steps = List.copyOf(steps)` for defensive copy
    - File: `src/main/java/com/latencylab/model/Scenario.java`
    - _Requirements: 3.2_

  - [x]* 4.2 Write property tests for `Scenario` — Properties 3, 4, 8
    - File: `src/test/java/com/latencylab/model/ScenarioPropertyTest.java`
    - **Property 3: Scenario construction preserves all field values**
    - **Validates: Requirements 3.2**
    - **Property 4: Scenario rejects invalid numeric bounds**
    - **Validates: Requirements 3.2**
    - **Property 8: Record immutability — steps list is defensively copied**
    - **Validates: Requirements 3.2**
    - Use `@Property(tries = 100)`, `@ForAll`, `@IntRange`, custom `@Provide` arbitraries for `RequestStep`
    - Tag each `@Property` with comment: `// Feature: latencylab-phase1-setup, Property N: <property_text>`

- [x] 5. Create `MetricsSnapshot` record
  - [x] 5.1 Implement `MetricsSnapshot` record in `com.latencylab.model`
    - Components: `totalRequests`, `successfulRequests`, `failedRequests`, `avgLatencyNanos`, `minLatencyNanos`, `maxLatencyNanos`, `p50LatencyNanos`, `p95LatencyNanos`, `p99LatencyNanos` (all long), `requestsPerSecond` (double), `snapshotTimestamp` (long)
    - Compact constructor: enforce `successfulRequests + failedRequests <= totalRequests`, throw `IllegalArgumentException` if violated
    - File: `src/main/java/com/latencylab/model/MetricsSnapshot.java`
    - _Requirements: 3.4_

  - [x]* 5.2 Write property tests for `MetricsSnapshot` — Properties 5, 6
    - File: `src/test/java/com/latencylab/model/MetricsSnapshotPropertyTest.java`
    - **Property 5: MetricsSnapshot request count invariant**
    - **Validates: Requirements 3.4**
    - **Property 6: MetricsSnapshot construction preserves all field values**
    - **Validates: Requirements 3.4**
    - Use `@Property(tries = 100)`, `@ForAll`, `@LongRange`, custom `@Provide` arbitraries to generate valid and invalid count combinations
    - Tag each `@Property` with comment: `// Feature: latencylab-phase1-setup, Property N: <property_text>`

- [x] 6. Create `VirtualUser` record
  - [x] 6.1 Implement `VirtualUser` record in `com.latencylab.model`
    - Components: `userId` (String, non-null), `state` (VirtualUserState, non-null), `activeScenario` (Scenario, nullable), `metricsSnapshot` (MetricsSnapshot, nullable)
    - Compact constructor: `Objects.requireNonNull` for `userId` and `state`
    - File: `src/main/java/com/latencylab/model/VirtualUser.java`
    - _Requirements: 3.1_

  - [x]* 6.2 Write property tests for `VirtualUser` — Property 7
    - File: `src/test/java/com/latencylab/model/VirtualUserPropertyTest.java`
    - **Property 7: VirtualUser construction preserves all field values**
    - **Validates: Requirements 3.1**
    - Use `@Property(tries = 100)`, `@ForAll`, `@StringNotEmpty`, custom `@Provide` arbitraries for `VirtualUserState`, nullable `Scenario`, nullable `MetricsSnapshot`
    - Tag each `@Property` with comment: `// Feature: latencylab-phase1-setup, Property 7: <property_text>`

- [x] 7. Checkpoint — compile and verify models
  - Ensure all model types compile cleanly with `mvn compile`. Ask the user if questions arise.

- [x] 8. Create component interfaces and supporting types
  - [x] 8.1 Create `ScenarioParser` interface in `com.latencylab.parser`
    - Methods: `Scenario parse(String filePath)`, `boolean validate(Scenario scenario)`
    - File: `src/main/java/com/latencylab/parser/ScenarioParser.java`
    - _Requirements: 4.1_

  - [x] 8.2 Create `SchedulerState` enum and `LoadScheduler` interface in `com.latencylab.scheduler`
    - `SchedulerState` values: `IDLE`, `RUNNING`, `PAUSED`, `STOPPED`
    - `LoadScheduler` methods: `void start(Scenario scenario)`, `void pause()`, `void stop()`, `SchedulerState getState()`
    - Files: `src/main/java/com/latencylab/scheduler/SchedulerState.java`, `src/main/java/com/latencylab/scheduler/LoadScheduler.java`
    - _Requirements: 4.2_

  - [x] 8.3 Create `VirtualUserEngine` interface in `com.latencylab.engine`
    - Methods: `List<VirtualUser> initialize(Scenario scenario, int userCount)`, `void execute(List<VirtualUser> users, Scenario scenario)`
    - File: `src/main/java/com/latencylab/engine/VirtualUserEngine.java`
    - _Requirements: 4.3_

  - [x] 8.4 Create `HttpResponseResult` record and `HttpTransportLayer` interface in `com.latencylab.transport`
    - `HttpResponseResult` components: `statusCode` (int), `responseBody` (String, nullable), `latencyNanos` (long)
    - `HttpTransportLayer` method: `HttpResponseResult execute(RequestStep step)`
    - Files: `src/main/java/com/latencylab/transport/HttpResponseResult.java`, `src/main/java/com/latencylab/transport/HttpTransportLayer.java`
    - _Requirements: 4.4_

  - [x] 8.5 Create `MetricsEngine` interface in `com.latencylab.metrics`
    - Methods: `void record(long latencyNanos, boolean success)`, `MetricsSnapshot snapshot()`
    - File: `src/main/java/com/latencylab/metrics/MetricsEngine.java`
    - _Requirements: 4.5_

  - [x] 8.6 Create `ReportingEngine` interface in `com.latencylab.reporting`
    - Methods: `void printConsole(MetricsSnapshot snapshot)`, `void writeCsv(MetricsSnapshot snapshot, String outputPath)`, `void writeJson(MetricsSnapshot snapshot, String outputPath)`
    - File: `src/main/java/com/latencylab/reporting/ReportingEngine.java`
    - _Requirements: 4.6_

- [x] 9. Create `LatencyLabMain` CLI entry point
  - [x] 9.1 Implement `LatencyLabMain` class in `com.latencylab.cli`
    - Obtain SLF4J logger via `LoggerFactory.getLogger(LatencyLabMain.class)`
    - Log startup banner at INFO level including `"LatencyLab"` and version string
    - Read `Implementation-Version` from JAR manifest; fall back to `"unknown"` if absent or unreadable
    - Parse `args[]` for `--config <path>`: if present log path at INFO; if `--config` is last token with no following value, log ERROR and call `System.exit(1)`
    - With no arguments, exit normally (code 0)
    - Wrap all logging in try/catch so logging exceptions do not propagate to the JVM
    - File: `src/main/java/com/latencylab/cli/LatencyLabMain.java`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_

- [x] 10. Create `logback.xml` logging configuration
  - Create `src/main/resources/logback.xml`
  - Define `CONSOLE` appender with pattern `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`
  - Set root log level to `INFO` with `<appender-ref ref="CONSOLE" />`
  - Set `com.latencylab` package log level to `DEBUG`
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

- [x] 11. Write unit tests for data models
  - [x] 11.1 Write `RequestStepTest` in `com.latencylab.model` (src/test)
    - Test valid construction and accessor round-trip
    - Test null `name`, `method`, `endpoint`, `headers` each throw `NullPointerException`
    - Test boundary values: `timeoutMillis` 1 and 300000 are valid; 0 and 300001 throw `IllegalArgumentException`
    - Test headers map is defensively copied (mutating original does not affect record)
    - File: `src/test/java/com/latencylab/model/RequestStepTest.java`
    - _Requirements: 7.1, 3.3_

  - [x] 11.2 Write `ScenarioTest` in `com.latencylab.model` (src/test)
    - Test valid construction and accessor round-trip
    - Test empty `steps` list throws `IllegalArgumentException`
    - Test out-of-range `rampUpSeconds`, `durationSeconds`, `userCount` each throw `IllegalArgumentException`
    - Test steps list is defensively copied
    - File: `src/test/java/com/latencylab/model/ScenarioTest.java`
    - _Requirements: 7.1, 3.2_

  - [x] 11.3 Write `MetricsSnapshotTest` in `com.latencylab.model` (src/test)
    - Test valid construction and accessor round-trip
    - Test `successfulRequests + failedRequests > totalRequests` throws `IllegalArgumentException`
    - Test boundary: `successfulRequests + failedRequests == totalRequests` is valid
    - File: `src/test/java/com/latencylab/model/MetricsSnapshotTest.java`
    - _Requirements: 7.1, 3.4_

  - [x] 11.4 Write `VirtualUserTest` in `com.latencylab.model` (src/test)
    - Test valid construction and accessor round-trip (including null `activeScenario` and null `metricsSnapshot`)
    - Test null `userId` throws `NullPointerException`
    - Test null `state` throws `NullPointerException`
    - File: `src/test/java/com/latencylab/model/VirtualUserTest.java`
    - _Requirements: 7.1, 3.1_

- [x] 12. Write interface presence and CLI tests
  - [x] 12.1 Write `InterfacePresenceTest` in `com.latencylab` (src/test)
    - Call `Class.forName(...)` for all 6 interfaces: `com.latencylab.parser.ScenarioParser`, `com.latencylab.scheduler.LoadScheduler`, `com.latencylab.engine.VirtualUserEngine`, `com.latencylab.transport.HttpTransportLayer`, `com.latencylab.metrics.MetricsEngine`, `com.latencylab.reporting.ReportingEngine`
    - Assert no `ClassNotFoundException` is thrown for any of them
    - File: `src/test/java/com/latencylab/InterfacePresenceTest.java`
    - _Requirements: 7.2, 4.1–4.6_

  - [x]* 12.2 Write `LatencyLabMainTest` in `com.latencylab.cli` (src/test)
    - Test no-args execution completes without throwing
    - Test `--config path/to/file.yaml` logs the path (capture log output or verify no exception)
    - Test `--config` with no following argument triggers `System.exit(1)` (use a `SecurityManager` stub or process-level test)
    - File: `src/test/java/com/latencylab/cli/LatencyLabMainTest.java`
    - _Requirements: 5.2, 5.3, 5.6, 5.7_

- [x] 13. Final Checkpoint — full `mvn verify`
  - Run `mvn verify` and confirm `BUILD SUCCESS` with a non-zero test count reported by Surefire. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation at natural boundaries
- Property tests (jqwik) validate universal correctness properties across 100+ generated inputs
- Unit tests validate specific examples, boundary values, and error conditions
- `pom.xml` must be created first — all subsequent compilation depends on it
- Model records must be created before interfaces that reference them (e.g., `RequestStep` before `Scenario`, both before `ScenarioParser`)
- `LatencyLabMain` depends on SLF4J being on the classpath (provided by `pom.xml`) and `logback.xml` being present for clean console output

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["3.2", "4.1"] },
    { "id": 4, "tasks": ["4.2", "5.1"] },
    { "id": 5, "tasks": ["5.2", "6.1"] },
    { "id": 6, "tasks": ["6.2", "8.1", "8.2", "8.3", "8.4", "8.5", "8.6"] },
    { "id": 7, "tasks": ["9.1", "10"] },
    { "id": 8, "tasks": ["11.1", "11.2", "11.3", "11.4", "12.1"] },
    { "id": 9, "tasks": ["12.2"] }
  ]
}
```
