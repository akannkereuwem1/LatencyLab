# LatencyLab — Project Structure

## Maven Layout

```
latencylab/
├── pom.xml
├── PRD.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/latencylab/
│   │   │       ├── cli/          # CLI entry point (LatencyLabMain)
│   │   │       ├── model/        # Core data models and enums
│   │   │       ├── parser/       # ScenarioParser interface
│   │   │       ├── scheduler/    # LoadScheduler interface + SchedulerState enum
│   │   │       ├── engine/       # VirtualUserEngine interface
│   │   │       ├── transport/    # HttpTransportLayer interface + HttpResponseResult
│   │   │       ├── metrics/      # MetricsEngine interface
│   │   │       └── reporting/    # ReportingEngine interface
│   │   └── resources/
│   │       └── logback.xml       # Logback configuration
│   └── test/
│       └── java/
│           └── com/latencylab/   # Test packages mirror production packages
│               ├── model/
│               ├── parser/
│               └── ...
└── .kiro/
    ├── specs/                    # Spec-driven development documents
    └── steering/                 # AI assistant guidance files
```

## Package Responsibilities

| Package | Contents |
|---|---|
| `com.latencylab.cli` | `LatencyLabMain` — the `main()` entry point |
| `com.latencylab.model` | `VirtualUser`, `Scenario`, `RequestStep`, `MetricsSnapshot` records; `VirtualUserState`, `HttpMethod` enums |
| `com.latencylab.parser` | `ScenarioParser` interface |
| `com.latencylab.scheduler` | `LoadScheduler` interface, `SchedulerState` enum (`IDLE`, `RUNNING`, `PAUSED`, `STOPPED`) |
| `com.latencylab.engine` | `VirtualUserEngine` interface |
| `com.latencylab.transport` | `HttpTransportLayer` interface, `HttpResponseResult` type |
| `com.latencylab.metrics` | `MetricsEngine` interface |
| `com.latencylab.reporting` | `ReportingEngine` interface |

## Key Conventions

- **Data models are Java records** — immutable, use compact constructors for validation
- **All major components are interface-first** — concrete implementations added in later phases and must declare `implements <InterfaceName>`
- **Test packages mirror production packages** — a test for `com.latencylab.model.VirtualUser` lives in `com.latencylab.model` under `src/test/java`
- **No implementation logic in Phase 1** — only scaffold, interfaces, data models, and wiring
- **Timing uses `System.nanoTime()`** — never `System.currentTimeMillis()` for latency measurement
- **Root package** `com.latencylab` must always contain at least one declared Java type

## Scenario Config Format (YAML)

```yaml
testName: ExampleTest
users: 5000
durationSeconds: 300
rampUpSeconds: 60
baseUrl: https://api.example.com
scenario:
  - step:
      name: login
      method: POST
      endpoint: /login
  - step:
      name: fetchFeed
      method: GET
      endpoint: /feed
```
