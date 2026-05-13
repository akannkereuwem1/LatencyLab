# LatencyLab

LatencyLab is a high-performance, lightweight load-testing CLI tool built in **Java 21**. It is designed to be exceptionally fast and efficient, prioritizing low startup latency and minimal resource usage by strictly avoiding heavy frameworks (like Spring Boot) and instead leveraging modern Java features such as Virtual Threads (Project Loom).

## Features

LatencyLab provides a robust, heavily tested foundation for load testing with the following features:

*   **Immutable Data Models:** Thread-safe models built using Java `record` types (`Scenario`, `RequestStep`, `MetricsSnapshot`, `VirtualUser`).
*   **Correct-by-Construction Validation:** Compact constructors strictly enforce logical boundaries, numerical limits, and defensive copying to guarantee data integrity across threads.
*   **Property-Based Testing Suite:** Comprehensive tests using **jqwik** and JUnit 5 to continuously validate logic against thousands of edge-case variations.
*   **Modular Architecture:** Cleanly defined component interfaces (`ScenarioParser`, `LoadScheduler`, `VirtualUserEngine`, `HttpTransportLayer`, `MetricsEngine`, `ReportingEngine`).
*   **CLI Scaffold:** A native, dependency-minimal CLI entry point with configured SLF4J / Logback integration for ultra-fast startup.
*   **HTTP Execution Engine (Phase 2):** 
    *   `OkHttpTransportLayer`: High-performance HTTP transport layer backed by OkHttp 4.12.0 with connection pooling, timeout handling, and idempotent close.
    *   `DefaultVirtualUserEngine`: Virtual user orchestration engine using Java 21 virtual threads for lightweight concurrency.
    *   Comprehensive test suite including unit tests and property-based tests (jqwik) ensuring correctness and reliability.

## Prerequisites

*   [Java Development Kit (JDK) 21+](https://jdk.java.net/21/)
*   [Apache Maven 3.8+](https://maven.apache.org/)

## Build Instructions

LatencyLab uses standard Maven lifecycle commands. To compile the code, run tests, and package the application:

```bash
mvn clean verify
```

This command will:
1. Compile the project using Java 21.
2. Execute the JUnit 5 unit tests and jqwik property tests.
3. Package the application into an executable Uber-JAR using the Maven Shade Plugin.

The compiled artifact will be available at:
`target/latencylab-0.1.0-SNAPSHOT.jar`

## Usage

You can run the CLI tool directly from the generated Uber-JAR. The CLI parses basic arguments and prepares the environment for the simulation engine.

```bash
java -jar target/latencylab-0.1.0-SNAPSHOT.jar --config path/to/scenario.yaml
```

## Architecture & Technology Stack

*   **Language:** Java 21
*   **HTTP Client:** OkHttp 4.12
*   **Data Parsing:** Jackson (YAML)
*   **Logging:** SLF4J API + Logback Classic
*   **Testing:** JUnit 5 (Jupiter), jqwik (Property-Based Testing)
*   **Build Tool:** Maven (Shade, Compiler, Surefire Plugins)
