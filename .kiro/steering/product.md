# LatencyLab — Product Summary

LatencyLab is a Java-based CLI performance testing harness for simulating synthetic mobile API traffic against backend systems. It targets QA engineers, backend developers, DevOps engineers, and researchers who need lightweight, customizable load testing without the overhead of enterprise tools.

## Core Capabilities

- Simulate thousands of concurrent virtual users via Java virtual threads
- Execute configurable REST API request scenarios defined in YAML
- Collect latency (avg, min, max, P50/P95/P99), throughput (RPS/TPS), and error-rate metrics
- Support multiple load profiles: constant, ramp-up, spike, stress, soak
- Generate reports in console summary, CSV, and JSON formats
- Assert performance thresholds (e.g., P95 < 500ms, error rate < 1%)

## Development Phases

| Phase | Deliverable |
|-------|-------------|
| 1 | Architecture and project setup (scaffold, interfaces, data models) |
| 2 | HTTP execution engine |
| 3 | Virtual user concurrency |
| 4 | Metrics aggregation |
| 5 | Reporting engine |
| 6 | Assertions and validation |
| 7 | Optimization and stress testing |

## V1 Scope Boundaries

**In scope:** REST API testing, concurrent virtual users, YAML scenario config, latency/throughput/error metrics, CSV/JSON/console reports, load profiles, performance assertions, JVM metrics.

**Out of scope:** Browser automation, device emulation, graphical UI, distributed agents, Kubernetes, WebSocket/gRPC testing, plugin marketplace.
