# LatencyLab Phase 1: Architecture & Project Setup Walkthrough

I have successfully completed the entirety of Phase 1! We now have a robust, high-performance foundation built on Java 21, ready for the core engine implementation in Phase 2.

Here is a summary of what was accomplished during this phase.

## 1. Project Scaffold and Build
We initialized a strict `pom.xml` using Maven that targets **Java 21**. 
- Dependencies are intentionally kept lightweight: `okhttp` for HTTP requests, `jackson-dataformat-yaml` for parsing, and `slf4j-api` / `logback-classic` for logging.
- For testing, we integrated JUnit 5 alongside **jqwik** for property-based testing.
- Configured the Maven Shade Plugin to build an executable Uber-JAR (`target/latencylab-0.1.0-SNAPSHOT.jar`).

## 2. Immutable Data Models
We implemented all core models as thread-safe Java `record` types. This ensures there are no unintended side effects when handling heavy concurrent simulation loads.
- **`RequestStep`**: Represents an individual HTTP request configuration with defensive copies of its headers.
- **`Scenario`**: Aggregates `RequestStep`s, applying validation on negative numeric boundaries (e.g. `rampUpSeconds`, `durationSeconds`).
- **`MetricsSnapshot`**: Contains robust invariant validation preventing logic bugs (like successful + failed requests exceeding the total) via overflow-safe arithmetic.
- **`VirtualUser`**: Manages the state of a simulated user and enforces `null` checks on its critical references.

## 3. Core Component Interfaces
To enforce strict separation of concerns, we established the system's modular architecture:
- `ScenarioParser`
- `LoadScheduler` (and the `SchedulerState` record)
- `VirtualUserEngine`
- `HttpTransportLayer` (and the `HttpResponseResult` record)
- `MetricsEngine`
- `ReportingEngine`

## 4. CLI Entry Point & Logging
We scaffolded `LatencyLabMain` to safely handle CLI arguments and initialize logging. 
- It reads the application version directly from the JAR manifest attributes.
- Implements strict error handling to avoid swallowing critical thread signals.
- Configured `logback.xml` to cleanly output `INFO` level logs to the console without verbose timestamps during normal execution.

## 5. Comprehensive Test Suite
This phase enforced rigorous test-driven guarantees:
- **Unit Tests**: Coverage for all models ensuring standard constraints, boundaries, and expected exception triggers.
- **Property Tests**: We leveraged `jqwik` to generate hundreds of randomized scenarios, virtual users, and request steps. This flushed out edge cases (such as handling enormous integers) and confirmed that our models are strictly immutable and defensively copied under stress.
- **Integration Validation**: `InterfacePresenceTest` and `LatencyLabMainTest` verify that the compiled Uber-JAR contains all required components and that the CLI properly responds to missing arguments by halting the process.

## Final Verification
The full suite passes cleanly with:
```bash
mvn clean verify
```
The codebase has been successfully committed. We are now ready to begin **Phase 2: Core Execution Engine** whenever you're ready!
