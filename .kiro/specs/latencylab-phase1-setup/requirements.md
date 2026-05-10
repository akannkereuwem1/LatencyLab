# Requirements Document

## Introduction

Phase 1 of LatencyLab establishes the foundational architecture and project setup for a Java-based API/backend performance testing harness. This phase covers the Maven project structure, core package layout, interface-based abstractions for all major components, dependency configuration, CLI entry point skeleton, core data model definitions, logging configuration, and build verification. No runtime execution logic is implemented in this phase — the goal is a clean, compilable scaffold that all subsequent phases build upon.

## Glossary

- **LatencyLab**: The Java-based performance testing harness being built.
- **Maven_Build**: The Apache Maven build tool used to compile, test, and package the project.
- **CLI_Entry_Point**: The `main` method class that serves as the command-line interface entry point for LatencyLab.
- **VirtualUser**: A data model representing a simulated mobile client, holding user ID, execution state, active scenario reference, and a metrics reference.
- **Scenario**: A data model representing an ordered request workflow, containing request steps, pacing rules, and assertions.
- **RequestStep**: A data model representing a single HTTP operation within a Scenario, containing HTTP method, endpoint, payload, headers, and timeout configuration.
- **MetricsSnapshot**: A data model representing a point-in-time capture of latency statistics, throughput metrics, error counts, and execution timestamps.
- **ScenarioParser**: The component interface responsible for reading and validating YAML/JSON configuration files and converting them into executable Scenario objects.
- **LoadScheduler**: The component interface responsible for concurrency control, ramp-up timing, pacing logic, and test lifecycle management.
- **VirtualUserEngine**: The component interface responsible for simulating concurrent mobile client behavior using Java virtual threads.
- **HttpTransportLayer**: The component interface responsible for HTTP communication, connection pooling, retry handling, and request execution via OkHttp.
- **MetricsEngine**: The component interface responsible for latency aggregation, throughput calculations, percentile computation, and error-rate tracking.
- **ReportingEngine**: The component interface responsible for generating CSV, JSON, and console summary reports.
- **SLF4J**: Simple Logging Facade for Java — the logging abstraction used by LatencyLab.
- **Logback**: The SLF4J-compatible logging implementation used at runtime.
- **OkHttp**: The HTTP client library used by the HttpTransportLayer.
- **Jackson_YAML**: The Jackson-based YAML parsing library used by the ScenarioParser.
- **JUnit5**: The unit testing framework used for build verification tests.
- **HttpResponseResult**: A named type in `com.latencylab.transport` representing the outcome of an HTTP request execution, containing at minimum the HTTP status code, response body, and latency in nanoseconds.
- **SchedulerState**: An enum in `com.latencylab.scheduler` representing the current execution state of the LoadScheduler, with at minimum the values: `IDLE`, `RUNNING`, `PAUSED`, `STOPPED`.

---

## Requirements

### Requirement 1: Maven Project Structure

**User Story:** As a developer, I want a standard Maven project structure, so that the project can be built, tested, and packaged using standard Maven commands.

#### Acceptance Criteria

1. WHEN `mvn package` is executed, THE Maven_Build SHALL produce an executable uber-JAR artifact with all runtime dependencies bundled, such that the JAR can be launched with `java -jar`.
2. THE Maven_Build SHALL be configured to use Java 21 as both the source and target compilation level via the `maven-compiler-plugin`.
3. THE Maven_Build SHALL include the following dependencies at compile/runtime scope: OkHttp, Jackson_YAML (jackson-dataformat-yaml), SLF4J API, and Logback Classic.
4. THE Maven_Build SHALL include JUnit5 (junit-jupiter) as a test-scoped dependency.
5. THE Maven_Build SHALL define the CLI_Entry_Point class (`com.latencylab.cli.LatencyLabMain`) as the main class in the JAR manifest via the `maven-jar-plugin` or equivalent packaging plugin configuration.
6. WHEN `mvn test` is executed and all tests pass, THE Maven_Build SHALL exit with code 0 and report the number of tests run, passed, and failed in the build output.
7. WHEN `mvn test` is executed and one or more tests fail, THE Maven_Build SHALL exit with a non-zero code and clearly identify the failing test classes and methods in the build output.

---

### Requirement 2: Core Package Layout

**User Story:** As a developer, I want a well-defined package hierarchy, so that each component has a clear home and the codebase remains navigable as it grows.

#### Acceptance Criteria

1. THE LatencyLab project SHALL define a root package of `com.latencylab`, containing at least one declared Java type.
2. THE LatencyLab project SHALL define the following sub-packages under `com.latencylab`, each containing at least one declared Java type (interface, class, or enum):
   - `cli` — for the CLI entry point
   - `model` — for core data models
   - `parser` — for the ScenarioParser interface and related types
   - `scheduler` — for the LoadScheduler interface and related types
   - `engine` — for the VirtualUserEngine interface and related types
   - `transport` — for the HttpTransportLayer interface and related types
   - `metrics` — for the MetricsEngine interface and related types
   - `reporting` — for the ReportingEngine interface and related types
3. THE LatencyLab project SHALL place all test classes under `src/test/java` in a package that exactly matches the package of the production type under test (e.g., a test for `com.latencylab.model.VirtualUser` SHALL reside in `com.latencylab.model` under `src/test/java`).

---

### Requirement 3: Core Data Models

**User Story:** As a developer, I want immutable, well-typed core data models, so that all components share a consistent domain vocabulary from the start.

#### Acceptance Criteria

1. THE LatencyLab project SHALL define `VirtualUser` as a Java record in `com.latencylab.model` with components: `userId` (String, non-null), `state` (VirtualUserState enum, non-null), `activeScenario` (Scenario, nullable — null when state is IDLE), and `metricsSnapshot` (MetricsSnapshot, nullable — null when no metrics have been recorded).
2. THE LatencyLab project SHALL define `Scenario` as a Java record in `com.latencylab.model` with components: `testName` (String, non-null), `steps` (List<RequestStep>, non-null, non-empty), `rampUpSeconds` (int, minimum 0, maximum 3600), `durationSeconds` (int, minimum 1, maximum 86400), and `userCount` (int, minimum 1, maximum 100000).
3. THE LatencyLab project SHALL define `RequestStep` as a Java record in `com.latencylab.model` with components: `name` (String, non-null), `method` (HttpMethod enum, non-null), `endpoint` (String, non-null), `body` (String, nullable), `headers` (Map<String, String>, non-null, may be empty), and `timeoutMillis` (int, minimum 1, maximum 300000).
4. THE LatencyLab project SHALL define `MetricsSnapshot` as a Java record in `com.latencylab.model` with components: `totalRequests` (long), `successfulRequests` (long), `failedRequests` (long), `avgLatencyNanos` (long), `minLatencyNanos` (long), `maxLatencyNanos` (long), `p50LatencyNanos` (long), `p95LatencyNanos` (long), `p99LatencyNanos` (long), `requestsPerSecond` (double), and `snapshotTimestamp` (long, captured via `System.nanoTime()`). The constraint `successfulRequests + failedRequests <= totalRequests` SHALL hold for any valid MetricsSnapshot instance.
5. THE LatencyLab project SHALL define a `VirtualUserState` enum in `com.latencylab.model` with at minimum the values: `IDLE`, `RUNNING`, `COMPLETED`, `FAILED`.
6. THE LatencyLab project SHALL define an `HttpMethod` enum in `com.latencylab.model` with values: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`.

---

### Requirement 4: Component Interface Abstractions

**User Story:** As a developer, I want interface-based abstractions for all core components, so that implementations can be swapped, mocked in tests, and developed independently across phases.

#### Acceptance Criteria

1. THE LatencyLab project SHALL define a `ScenarioParser` interface in `com.latencylab.parser` with: a method `parse(String filePath): Scenario` that reads and converts a YAML/JSON file into a Scenario object, and a method `validate(Scenario scenario): boolean` that returns true if the scenario is structurally valid and false otherwise.
2. THE LatencyLab project SHALL define a `LoadScheduler` interface in `com.latencylab.scheduler` with methods: `start(Scenario scenario): void`, `pause(): void`, `stop(): void`, and `getState(): SchedulerState`.
3. THE LatencyLab project SHALL define a `VirtualUserEngine` interface in `com.latencylab.engine` with methods: `initialize(Scenario scenario, int userCount): List<VirtualUser>` to create a pool of VirtualUser instances, and `execute(List<VirtualUser> users, Scenario scenario): void` to run the scenario concurrently.
4. THE LatencyLab project SHALL define an `HttpTransportLayer` interface in `com.latencylab.transport` with a method `execute(RequestStep step): HttpResponseResult` that performs the HTTP request described by the step and returns an `HttpResponseResult`.
5. THE LatencyLab project SHALL define a `MetricsEngine` interface in `com.latencylab.metrics` with methods: `record(long latencyNanos, boolean success): void` to capture a single request outcome, and `snapshot(): MetricsSnapshot` to produce a point-in-time MetricsSnapshot from all recorded data.
6. THE LatencyLab project SHALL define a `ReportingEngine` interface in `com.latencylab.reporting` with methods: `printConsole(MetricsSnapshot snapshot): void`, `writeCsv(MetricsSnapshot snapshot, String outputPath): void`, and `writeJson(MetricsSnapshot snapshot, String outputPath): void`.
7. WHEN a new phase introduces a concrete implementation of any component, THE implementation class SHALL declare `implements <InterfaceName>` for the corresponding Phase 1 interface, verifiable by reflection (i.e., `InterfaceName.class.isAssignableFrom(ImplementationClass.class)` returns true).

---

### Requirement 5: CLI Entry Point Skeleton

**User Story:** As a developer, I want a runnable CLI entry point, so that the project can be launched from the command line and the wiring point for future argument parsing is established.

#### Acceptance Criteria

1. THE CLI_Entry_Point SHALL be a class named `LatencyLabMain` in `com.latencylab.cli` containing a standard Java `main(String[] args)` method.
2. WHEN `LatencyLabMain` is executed with no arguments, THE CLI_Entry_Point SHALL log a startup message at INFO level using SLF4J that includes the application name "LatencyLab".
3. WHEN `LatencyLabMain` is executed with no arguments, THE CLI_Entry_Point SHALL exit with code 0.
4. IF the SLF4J logging call in the startup sequence throws an exception, THE CLI_Entry_Point SHALL still exit with code 0 and SHALL NOT propagate the exception to the JVM.
5. WHEN `LatencyLabMain` is executed, THE CLI_Entry_Point SHALL log the LatencyLab version string at INFO level, reading it from the JAR manifest `Implementation-Version` attribute; IF the attribute is absent or the manifest is unreadable, THE CLI_Entry_Point SHALL log the fallback string `"unknown"` instead.
6. WHEN `LatencyLabMain` is executed with the argument `--config <path>`, THE CLI_Entry_Point SHALL log an INFO message that includes the received path value, without performing any further processing in Phase 1.
7. IF `LatencyLabMain` is executed with `--config` as the last argument and no following path value, THE CLI_Entry_Point SHALL log an ERROR message indicating the missing path argument and exit with a non-zero exit code.

---

### Requirement 6: Logging Configuration

**User Story:** As a developer, I want a working logging configuration, so that all components can emit structured, leveled log output from day one.

#### Acceptance Criteria

1. THE LatencyLab project SHALL include a `logback.xml` configuration file in `src/main/resources`.
2. THE Logback configuration SHALL define a console appender named `CONSOLE` that outputs log messages in the pattern: `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`.
3. THE Logback configuration SHALL set the root log level to `INFO`.
4. THE Logback configuration SHALL set the `com.latencylab` package log level to `DEBUG` to support development-time diagnostics.
5. WHEN a class within `com.latencylab` calls `LoggerFactory.getLogger(ClassName.class)` and emits a log message at INFO or DEBUG level, THE log call SHALL produce output on the console in the configured pattern with no SLF4J provider warning printed to stderr.
6. THE Logback configuration SHALL reference the `CONSOLE` appender in the root logger element, ensuring all log output is routed to the console appender.

---

### Requirement 7: Build Verification

**User Story:** As a developer, I want a passing test suite that verifies the scaffold is correctly wired, so that I have confidence the project structure is sound before Phase 2 begins.

#### Acceptance Criteria

1. THE Maven_Build SHALL include at least one JUnit5 test class per core data model (`VirtualUser`, `Scenario`, `RequestStep`, `MetricsSnapshot`). Each test class SHALL assert that: (a) the model can be instantiated without throwing an exception, and (b) each accessor method returns the exact value supplied at construction.
2. THE Maven_Build SHALL include a JUnit5 test that verifies all six component interfaces (`ScenarioParser`, `LoadScheduler`, `VirtualUserEngine`, `HttpTransportLayer`, `MetricsEngine`, `ReportingEngine`) are present as loadable Java types by calling `Class.forName(fullyQualifiedName)` for each and asserting no `ClassNotFoundException` is thrown.
3. WHEN `mvn verify` is executed on a clean checkout, THE Maven_Build SHALL complete with `BUILD SUCCESS` and a non-zero test execution count reported by Maven Surefire.
4. WHEN `mvn verify` is executed and zero tests are discovered, THE Maven_Build SHALL fail with a non-zero exit code; this SHALL be enforced by configuring `failIfNoTests=true` in the Maven Surefire plugin.
5. THE Maven_Build SHALL configure the `maven-compiler-plugin` with `-Xlint:unchecked` and `-Xlint:deprecation` flags, causing the build to surface any unchecked or deprecation warnings as visible compiler output for code within the `com.latencylab` package.
