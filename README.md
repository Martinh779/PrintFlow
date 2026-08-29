# PrintFlow

PrintFlow is a distributed print job manager built around a Spring Boot REST service, a thread-safe dispatcher, and TCP-based printer processes.

## Scope
- REST API for creating, listing, fetching, cancelling, and reporting jobs
- Central job repository and scheduler logic
- Dispatcher with round-robin, least-loaded, and priority-aware selection
- TCP printer registration and status updates
- Printer failover and recovery for assigned jobs
- Performance client and module-based build structure

## Modules
- `server` for the REST API, dispatcher, job management, and TCP socket server
- `printer-process` for the simulated printer client that connects to the server
- `performance-client` for load generation and throughput metrics
- `shared-model` for the shared domain model, status machine, and protocol DTOs

## Runbook and project readiness

The project documentation includes operational guidance, configuration notes, benchmark execution details, and current project risks:

- `docs/runbook-and-project-readiness.md`
- `docs/CODEBASE_GUIDE.md` for the architecture, code walkthrough, lifecycle flow, TCP protocol, and extension guide for new contributors

## Build and test
```bash
./mvnw test
```

## Run locally
Start the server:
```bash
./mvnw -pl server spring-boot:run
```

Start a simulated printer:
```bash
./mvnw -pl printer-process spring-boot:run
```

The server listens on the configured socket port and accepts printer registrations over TCP.

The admin cockpit is available here:

```text
http://localhost:8081/admin
```

You can create bulk test jobs with:

```text
POST /api/admin/jobs/bulk
```

## Dispatch strategy configuration

The default strategy is defined in `server/src/main/resources/application.yaml`:

```yaml
printflow:
  dispatch:
    strategy: round-robin
```

Supported strategy keys:
- `round-robin` (default and fallback)
- `least-loaded`
- `priority-aware`

You can inspect and change the runtime policy through the Admin API:
- `GET /api/admin/dispatch-policy`
- `PUT /api/admin/dispatch-policy` with body `{"strategy":"least-loaded"}`

How the strategies differ:
- `round-robin` keeps fairness for generic load
- `least-loaded` optimizes active-assignment distribution
- `priority-aware` evaluates requested `PrinterProfile` matching and job priority thresholds (`>=7`, `>=4`, else low) when choosing among candidate printers

## Performance load runner

Run the performance client as a load runner:

```bash
./mvnw -pl performance-client spring-boot:run -Dspring-boot.run.arguments="--load-runner"
```

Override the load profile and scenario set:

```bash
./mvnw -pl performance-client spring-boot:run -Dspring-boot.run.arguments="--load-runner --profile=stress --printers=1,2,4 --output=performance-client/target/stress-report.json"
```

The load runner uses configurable profiles from `performance-client/src/main/resources/application.yaml`,
executes the configured printer scenarios (default `1,2,4`), and prints structured JSON metrics including:
- request latency (`min`, `avg`, `p50`, `p95`, `max`)
- submission throughput and completed-job throughput
- success rate and error counts
- job outcomes (`completed`, `failed`, `cancelled`, `timedOut`)

## NFA benchmark suite

`test/nfa-benchmark-suite` is a mix of benchmark report checks and server-side NFA tests:

- `performance-client` verifies benchmark thresholds from generated metrics (`NFA-01` to `NFA-06`) via `nfaEvaluation` in the load-runner JSON report
- `server` verifies consistency and startup behavior (`NFA-04`, `NFA-05`, `NFA-07`) with automated tests

Run the suite:

```bash
./mvnw -pl performance-client test -Dtest=NfaBenchmarkEvaluatorTest
./mvnw -pl server test -Dtest=NfaBenchmarkSuiteTest
```

Generate a benchmark artifact with automatic NFA evaluation:

```bash
./mvnw -pl performance-client spring-boot:run -Dspring-boot.run.arguments="--load-runner --profile=stress --printers=1,2,4 --output=performance-client/target/nfa-stress-report.json"
```

The JSON report includes:
- `scenarios[*]` with success, latency, throughput, and outcome counters
- `nfaEvaluation.overallPass` and per-check pass/fail details for `NFA-01` to `NFA-06`
