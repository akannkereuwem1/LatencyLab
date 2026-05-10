# LatencyLab — Tech Stack

## Language & Runtime

- **Java 21** — source and target compilation level
- Java virtual threads (Project Loom) for concurrent user simulation

## Build Tool

- **Apache Maven** — compile, test, package

## Dependencies

| Dependency | Purpose |
|---|---|
| OkHttp | HTTP client for the transport layer |
| jackson-dataformat-yaml | YAML scenario config parsing |
| SLF4J API | Logging abstraction |
| Logback Classic | SLF4J runtime implementation |
| JUnit 5 (junit-jupiter) | Unit testing (test scope only) |

## Compiler Flags

The `maven-compiler-plugin` is configured with `-Xlint:unchecked` and `-Xlint:deprecation` — these warnings will surface in build output and should not be ignored.

## Common Commands

```bash
# Compile the project
mvn compile

# Run all tests
mvn test

# Package into an executable uber-JAR (includes all runtime deps)
mvn package

# Full build + verify (compile, test, package)
mvn verify

# Run the JAR
java -jar target/latencylab-<version>.jar

# Run with a scenario config
java -jar target/latencylab-<version>.jar --config path/to/scenario.yaml
```

## Logging

- SLF4J + Logback configured via `src/main/resources/logback.xml`
- Root log level: `INFO`
- `com.latencylab` package log level: `DEBUG`
- Console output pattern: `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`
- Always obtain loggers via `LoggerFactory.getLogger(ClassName.class)`

## Testing

- JUnit 5 — use `@Test`, `@BeforeEach`, etc. from `org.junit.jupiter.api`
- Test classes live under `src/test/java` in the same package as the class under test
- `failIfNoTests=true` is enforced in Maven Surefire — zero discovered tests fails the build
- `mvn verify` on a clean checkout must exit with `BUILD SUCCESS`
