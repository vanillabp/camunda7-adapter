# VanillaBP adapter for Camunda 7

This is the [VanillaBP](https://www.vanillabp.io) Version 2 adapter for
[Camunda 7](https://camunda.com/), the embedded workflow engine.

> **Status: Version 2, in development.** BPMN deployment and starting workflows are
> implemented (embedded engine, in the caller's transaction) and work end-to-end through
> `ProcessService.startWorkflow`. Task wiring (`@WorkflowTask`), message correlation and
> BPMS-election awareness are not implemented yet and their SPI methods throw
> `UnsupportedOperationException`.

## Coordinates

The adapter is built on top of `adapter-platform-integration` (the platform-neutral
migration adapter plus the Spring Boot integration).

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

|    Module     |            Artifact            |                        Contents                         |
|---------------|--------------------------------|---------------------------------------------------------|
| `core`        | `camunda7-adapter`             | Platform-neutral SPI implementations + engine wiring.   |
| `spring-boot` | `camunda7-adapter-spring-boot` | Spring Boot auto-configuration registering the adapter. |

## Configuration

The adapter is a VanillaBP adapter of type `camunda7`. Configure an adapter instance by
giving it an id and pointing its `type` at `camunda7`:

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
  prioritized-adapters:
    - c7
```

The same type may be configured under several ids (e.g. two Camunda 7 engines side by
side during a migration) - **each id gets its OWN embedded engine** (named
`vanillabp-camunda7-<id>`). Since two embedded engines must never share one database
schema (they would be the same engine state), every additional id needs its own
datasource - the boot fails with a guiding message otherwise.

Per-adapter-id settings (all optional, at the canonical location
`vanillabp.adapters.<id>.*`):

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
      # create/upgrade the engine schema on boot (engine values, e.g. true, false,
      # create-drop); default: true
      database-schema-update: true
      # engine-wide default history time-to-live (Camunda 7.24 rejects deployments
      # of processes without one); default: P180D; a process may override it via
      # camunda:historyTimeToLive
      history-time-to-live: P180D
      # OPTIONAL: an own datasource for this id's engine (required for every
      # additional camunda7 id - see the transaction caveat below!)
      data-source:
        url: jdbc:postgresql://legacy-host/legacy-db
        username: camunda
        password: ...
        driver-class-name: org.postgresql.Driver
```

BPMN files are read from each workflow module's configured `resources-location` and
deployed to the embedded engine of every prioritized adapter.

### Behaviour

- **BPMN deployment.** On boot the adapter reads every executable BPMN process
  (`<bpmn:process isExecutable="true">`; a file may contain several) of each workflow
  module and deploys them as a single Camunda deployment. The **workflow module ID is
  used as the Camunda tenant ID** (Version-1 behaviour) so BPMN process ids are isolated
  between modules. Duplicate filtering is enabled, so unchanged models are not
  redeployed on every boot.
- **Starting a workflow (in the local transaction).** The embedded engine shares the
  application's data source and transaction manager, so a started process instance is
  committed or rolled back **together with the workflow aggregate**. The
  workflow-aggregate ID becomes the Camunda **business key**, the workflow module ID the
  **tenant ID**. There is no two-phase commit and no transaction outbox
  (`needsTwoPhaseCommitForStartingWorkflows()` is `false`).
- **Asynchronous continuations (job executor).** Each engine runs an idiomatic
  `SpringJobExecutor` on a managed thread pool (thread names contain the adapter id).
  Activation is deferred: the executor starts when the deployment pipeline starts
  workflow processing (after the application is ready) and stops on graceful shutdown
  once the last workflow module stopped - before the engine closes. (An immediate
  wake-up after commits creating jobs - Version 1's `WakeupJobExecutor` - is a planned
  follow-up; until then new jobs are picked up by the executor's regular acquisition.)
- **Transaction caveat for ids with an OWN datasource.** An engine on its own
  datasource CANNOT join the caller's transaction (its engine commands commit on the
  engine's own transaction manager). Starting workflows through such an adapter id
  therefore uses VanillaBP's regular **two-phase start**
  (`needsTwoPhaseCommitForStartingWorkflows()` is `true`): phase one does nothing
  against the engine, phase two - dispatched via the phase-two outbox after the
  caller's commit - starts the instance idempotently (skipped if a running instance
  with the same business key/tenant exists; like every outbox-based operation this
  keeps an at-least-once residual window). This prevents ghost process instances that
  a phase-one start would leave behind on rollback. The in-transaction guarantee above
  applies ONLY to ids sharing the application's datasource - acceptable for the
  migration scenario, where the OLD engine mostly continues existing instances. Note
  that an application using such an adapter id needs a phase-two outbox (provided by
  the VanillaBP platform integration for JPA/JDBC and MongoDB setups).

### Embedded-engine wiring

The `spring-boot` module builds the engine(s) itself from
`org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration` (shipped by
`org.camunda.bpm:camunda-engine-spring-6`, whose Spring dependencies are `provided`, so
the application's Spring Boot 4.1 / Spring Framework 7 is used). The
`camunda-bpm-spring-boot-starter` is deliberately **not** used (see
[Known issues](#known-issues)). Each configured adapter id's engine:

- uses the application's `DataSource` (engine tables `ACT_*` live next to the
  aggregates) and the application's `PlatformTransactionManager` (engine commands join
  the caller's transaction — this in-transaction guarantee is the whole point of the
  C7 adapter) — UNLESS `vanillabp.adapters.<id>.data-source.*` configures an own
  datasource: then the adapter builds and owns a dedicated pool plus transaction
  manager for that engine (see the transaction caveat above),
- creates/upgrades its schema on boot (`database-schema-update`, default `true`),
- runs asynchronous continuations on a `SpringJobExecutor` backed by a dedicated
  managed thread pool, activated only while workflow processing is started, and
- applies a default history time-to-live (`history-time-to-live`, default `P180D`;
  Camunda 7.24 rejects deployments of processes without one; a process may still
  override it via `camunda:historyTimeToLive`).

A `DataSource` and a `PlatformTransactionManager` must be present (a Camunda 7
application always needs a database) unless every configured id brings its own
datasource.

## Supported Camunda version

Camunda **7.24** is the final feature release of Camunda 7 (October 2025, LTS). The
Camunda 7 community edition is **end-of-life** — no further community releases are
expected. This adapter pins Camunda `7.24.x`.

Camunda 7 runs **embedded** inside the application's JVM and shares the same database and
the same transaction as the business code. Consequently starting a workflow happens
completely within the local transaction (no two-phase commit / transaction outbox), and
engine queries are immediately consistent.

## Quarkus (JVM mode only!)

Both VanillaBP and the adapter are Quarkus extensions, so both must be added
explicitly:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>vanillabp-quarkus-integration</artifactId>
</dependency>
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-adapter-quarkus</artifactId>
</dependency>
```

The extension wires the **plain Camunda 7 engine** via the engine-shipped
`JakartaTransactionProcessEngineConfiguration` on the application's Agroal datasource
with the Narayana transaction manager — Camunda's own Quarkus extension is not used
(version-locked to older Quarkus releases). Engine commands join the caller's JTA
transaction, so the in-transaction guarantee holds like on Spring Boot; schema
operations run in their own JTA transaction (Agroal has no deferred enlistment).

**The Quarkus extension is JVM-mode only — native images are not supported** (the
engine stack — MyBatis, JUEL, scripting, reflective delegate instantiation — is
reflection-heavy; Camunda never supported native images and neither do the forks).

Configuration keys match the Spring Boot module (`database-schema-update`,
`history-time-to-live`), with ONE platform difference: an adapter id's own datasource
is referenced **by name** instead of by URL, because named Quarkus datasources are
build-time-declared:

```yaml
quarkus:
  datasource:            # the application's default datasource (aggregates + engine)
    db-kind: postgresql
    ...
    legacy:              # a second, named datasource for the OLD engine
      db-kind: postgresql
      ...
vanillabp:
  adapters:
    c7:
      type: camunda7     # runs on the default datasource (in-transaction guarantee)
    c7-legacy:
      type: camunda7
      data-source-name: legacy   # runs on its own schema (two-phase start, see the
                                 # transaction caveat above)
```

An unknown `data-source-name` and two adapter ids sharing one datasource fail the
boot with guiding messages.

## Known issues

- **`camunda-bpm-spring-boot-starter:7.24.0` is incompatible with the Spring Boot 4.1
  baseline.** VanillaBP Version 2 builds on Spring Boot 4.1.0, whereas the Camunda 7.24
  Spring Boot starter targets Spring Boot **3.5.5** (`version.spring-boot` in
  `org.camunda.bpm:camunda-parent:7.24.0`). Its auto-configuration is compiled against
  Spring Boot 3.x APIs that moved or were removed in Spring Boot 4. Therefore the
  `spring-boot` module wires the embedded engine itself (see
  [Embedded-engine wiring](#embedded-engine-wiring)) using
  `org.camunda.bpm:camunda-engine-spring-6` and does **not** use the starter.

## Test coverage

An aggregated JaCoCo report over all modules is generated by `mvn install verify`
into `test-coverage-report/report`. Baseline recorded with the hardening story
(2026-07-29): **90.0% line coverage**. The feature stories' definition of done
requires >90% - gaps are filled by the stories touching the respective code.
