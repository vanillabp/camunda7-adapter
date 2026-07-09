# VanillaBP adapter for Camunda 7

This is the [VanillaBP](https://www.vanillabp.io) Version 2 adapter for
[Camunda 7](https://camunda.com/), the embedded workflow engine.

> **Status: Version 2, in development.** BPMN deployment and starting workflows are
> implemented (embedded engine, in the caller's transaction). Task wiring
> (`@WorkflowTask`), message correlation and BPMS-election awareness are not implemented
> yet and their SPI methods throw `UnsupportedOperationException`. See
> [Known issues](#known-issues) for the one platform-integration gap that currently
> blocks the `ProcessService.startWorkflow` end-to-end path.

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
side during a migration).

BPMN files are read from each workflow module's configured `resources-location` and
deployed to the embedded engine.

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

### Embedded-engine wiring

The `spring-boot` module builds the engine itself from
`org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration` (shipped by
`org.camunda.bpm:camunda-engine-spring-6`, whose Spring dependencies are `provided`, so
the application's Spring Boot 4.1 / Spring Framework 7 is used). The
`camunda-bpm-spring-boot-starter` is deliberately **not** used (see
[Known issues](#known-issues)). The engine is configured to:

- use the application's `DataSource` (engine tables `ACT_*` live next to the aggregates),
- use the application's `PlatformTransactionManager` (engine commands join the caller's
  transaction — this in-transaction guarantee is the whole point of the C7 adapter),
- create/upgrade its schema on boot (`databaseSchemaUpdate = true`),
- run asynchronous continuations on the job executor (`jobExecutorActivate = true`), and
- apply a default history time-to-live of `P180D` (Camunda 7.24 rejects deployments of
  processes without one; a process may still override it via
  `camunda:historyTimeToLive`).

A `DataSource` and a `PlatformTransactionManager` must be present (a Camunda 7
application always needs a database).

## Supported Camunda version

Camunda **7.24** is the final feature release of Camunda 7 (October 2025, LTS). The
Camunda 7 community edition is **end-of-life** — no further community releases are
expected. This adapter pins Camunda `7.24.x`.

Camunda 7 runs **embedded** inside the application's JVM and shares the same database and
the same transaction as the business code. Consequently starting a workflow happens
completely within the local transaction (no two-phase commit / transaction outbox), and
engine queries are immediately consistent.

## No Quarkus module

This repository intentionally ships **only** `core` and `spring-boot` — there is no
Quarkus module. Camunda's own Quarkus extension is version-locked to older Quarkus
releases, and Camunda 7 is end-of-life, so investing in a Quarkus variant is not
worthwhile. VanillaBP applications on Quarkus should target a maintained BPMS adapter
(e.g. Camunda 8).

## Known issues

- **`ProcessService.startWorkflow` is blocked by a platform-integration gap.** The
  adapter SPI method
  `io.vanillabp.integration.adapter.spi.MigratableProcessService#startWorkflowPhaseOne(AggregatePersistenceAware, A)`
  receives only the workflow aggregate — not the workflow module ID nor the BPMN process
  ID — and `MigrationProcessService#startWorkflow(A)` does not thread them to the adapter
  either. An embedded engine needs the BPMN process ID (to select the process) and the
  module ID (as the Camunda tenant) to call
  `RuntimeService.startProcessInstanceByKey(...)`. The adapter's real start logic
  therefore lives in the directly-testable
  `Camunda7ProcessService#startProcessInstance(workflowModuleId, bpmnProcessId, aggregateId)`;
  `startWorkflowPhaseOne` throws until the SPI provides those two values. The integration
  test proves the start and the in-transaction rollback through that method; the
  end-to-end `processService.startWorkflow(...)` path is covered by a `@Disabled` test
  that turns green once the SPI is fixed centrally.
- **`camunda-bpm-spring-boot-starter:7.24.0` is incompatible with the Spring Boot 4.1
  baseline.** VanillaBP Version 2 builds on Spring Boot 4.1.0, whereas the Camunda 7.24
  Spring Boot starter targets Spring Boot **3.5.5** (`version.spring-boot` in
  `org.camunda.bpm:camunda-parent:7.24.0`). Its auto-configuration is compiled against
  Spring Boot 3.x APIs that moved or were removed in Spring Boot 4. Therefore the
  `spring-boot` module wires the embedded engine itself (see
  [Embedded-engine wiring](#embedded-engine-wiring)) using
  `org.camunda.bpm:camunda-engine-spring-6` and does **not** use the starter.

