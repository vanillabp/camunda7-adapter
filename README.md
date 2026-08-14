![Header](./readme/vanillabp-headline.png)

# VanillaBP adapter for Camunda 7

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This is the [VanillaBP](https://www.vanillabp.io) Version 2 adapter for
[Camunda 7](https://camunda.com/), the embedded workflow engine.

Developers who want to **use** this adapter should refer to the
[Wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki); the VanillaBP concepts it builds
on are documented in the [VanillaBP Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki). This
`README.md` is aimed at contributors.

> **Status: Version 2, in development.** BPMN deployment, starting workflows (including
> the starts the engine performs on its own), task processing (`@WorkflowTask`),
> completing/canceling asynchronous tasks, user tasks, message correlation, signals, the
> end-of-workflow notification, the aggregate sync (`@SyncWithBPMS`, `aggregateChanged`),
> the viewer/history API, process versions and the BPMS-election awareness probes are
> implemented, all of it on the embedded engine and in the caller's transaction. What this
> adapter does NOT deliver is listed under [Known deviations](#known-deviations).

## Documentation and supported platforms

This adapter runs on both platforms VanillaBP supports:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fcamunda7-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/camunda7-adapter/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fcamunda7-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/camunda7-adapter/quarkus-report)

Coverage is measured separately per platform - a platform's tests never cover the other
platform's code. Click a badge to open the respective report.

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

|        Module         |                Artifact                |                                                   Contents                                                   |
|-----------------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `core`                | `camunda7-adapter`                     | Platform-neutral SPI implementations + engine wiring.                                                        |
| `spring-boot`         | `camunda7-adapter-spring-boot`         | Spring Boot auto-configuration registering the adapter.                                                      |
| `spring-boot-webapps` | `camunda7-adapter-spring-boot-webapps` | Optional: serves Camunda's Cockpit, Tasklist and Admin at `/camunda` against the engines this adapter built. |

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
      # OPTIONAL: the name of an APPLICATION-PROVIDED datasource this id's engine
      # runs on (required for every additional camunda7 id - see the transaction
      # caveat below!). Setting up datasources is deliberately the application's
      # concern - VanillaBP never builds its own pool. Spring Boot: the name of a
      # DataSource bean; Quarkus: the name of a declared named Agroal datasource
      # (quarkus.datasource.<name>.*).
      data-source-name: legacy
```

On Spring Boot, declare the additional datasource bean with
`defaultCandidate = false` so it stays out of by-type injection and Spring Boot's
default-datasource auto-configuration stays active (the standard pattern for
additional application datasources):

```java
@Bean(defaultCandidate = false)
public DataSource legacy() {
    return DataSourceBuilder.create()...build();
}
```

BPMN files are read from each workflow module's configured `resources-location` and
deployed to the embedded engine of every prioritized adapter.

### Behaviour

- **BPMN deployment.** On boot the adapter reads every executable BPMN process
  (`<bpmn:process isExecutable="true">`; a file may contain several) of each workflow
  module and deploys them as a single Camunda deployment. Where the deployment lands is
  decided by the name-clash-avoidance mode, see
  [Keeping workflow modules apart](#keeping-workflow-modules-apart). Duplicate filtering
  is enabled, so unchanged models are not redeployed on every boot.
- **Starting a workflow (in the local transaction).** The embedded engine shares the
  application's data source and transaction manager, so a started process instance is
  committed or rolled back **together with the workflow aggregate**. The
  workflow-aggregate ID becomes the Camunda **business key**, and the tenant is whatever
  the [name-clash-avoidance mode](#keeping-workflow-modules-apart) says. There is no
  two-phase commit and no transaction outbox
  (`needsTwoPhaseCommitForStartingWorkflows()` is `false`).
- **Asynchronous continuations (job executor).** Each engine runs an idiomatic
  `SpringJobExecutor` on a managed thread pool (thread names contain the adapter id).
  Activation is deferred: the executor starts when the deployment pipeline starts
  workflow processing (after the application is ready) and stops on graceful shutdown
  once the last workflow module stopped - before the engine closes. (An immediate
  wake-up after commits creating jobs - Version 1's `WakeupJobExecutor` - is a planned
  follow-up; until then new jobs are picked up by the executor's regular acquisition.)
- **Transaction caveat for ids with an OWN (named) datasource.** An engine on a
  named datasource CANNOT join the caller's transaction (its engine commands commit
  on an adapter-internal transaction manager bound to that datasource). Starting
  workflows through such an adapter id therefore uses VanillaBP's regular
  **two-phase start**
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
  C7 adapter) — UNLESS `vanillabp.adapters.<id>.data-source-name` references an
  application-provided `DataSource` bean of that name: then the engine runs on that
  bean's datasource with an adapter-internal transaction manager (see the transaction
  caveat above; the adapter never builds a pool — a missing bean fails the boot
  listing the available `DataSource` beans),
- creates/upgrades its schema on boot (`database-schema-update`, default `true`),
- runs asynchronous continuations on a `SpringJobExecutor` backed by a dedicated
  managed thread pool, activated only while workflow processing is started, and
- applies a default history time-to-live (`history-time-to-live`, default `P180D`;
  Camunda 7.24 rejects deployments of processes without one; a process may still
  override it via `camunda:historyTimeToLive`).

A `DataSource` and a `PlatformTransactionManager` must be present (a Camunda 7
application always needs a database) unless every configured id brings its own
datasource.

## Task processing (execution model)

`@WorkflowTask` methods are wired to BPMN tasks by expression: implement a service
task as *Expression* `${myTaskDefinition}` (or *Delegate expression*) - the
expression text names the method's task definition (defaulting to the method
name). At deployment the adapter validates both directions (every task has a
method, every method matches a task of one of its class' BPMN processes) with
guiding messages, and forces `asyncBefore`/`asyncAfter` onto service-like tasks:
every task runs in its own job transaction, aligning the embedded engine with
remote BPMS.

Handlers run INSIDE the engine's job transaction (Spring-managed respectively JTA
on Quarkus, with the CDI request context activated): the workflow aggregate is
loaded by the business key, the method invoked with bound parameters and the
aggregate saved - business changes and engine state commit or roll back together.
Outcomes:

- normal return - the task completes;
- `TaskException` - the task completes with a BPMN error (error-boundary
  routing); the aggregate changes COMMIT (V1 contract - do not add your own
  `@Transactional`);
- any other exception - the job transaction rolls back and the job executor
  retries (finally: incident);
- methods declaring `@TaskId` leave the task open (asynchronous completion via
  `ProcessService#completeTask`) - such tasks have to be wired by
  *Delegate expression* (an *Expression* task completes when the expression
  returns and could never stay open).

**The application does not start on that last defect (story 50).** While wiring,
the adapter asks the core whether the method serving a task completes
asynchronously (`WorkflowTaskInvoker#workflowTaskCompletesAsynchronously`,
answered by the `WorkflowTaskRegistry` from the method's `@TaskId` parameter) and
aborts the deployment for every task wired by *Expression* whose method wants to
keep it open. `Camunda7TaskELResolver` keeps the same guard for a model that
reached the engine another way, and both report the identical message, which lives
once in `Camunda7TaskConnectable#asynchronousTaskWiredByExpression`. The reverse
case is deliberately silent: *Delegate expression* serves a method without
`@TaskId` just as well, because the behavior leaves the activity when the handler
returns.

**Completing/canceling async tasks (`ProcessService#completeTask`/`#cancelTask`,
story 22):** the `@TaskId` value is the parked execution's ID; completing signals
that execution, canceling signals it with the adapter's cancel marker and the
behavior propagates the BPMN error (error-boundary routing). Both run ENTIRELY
within the caller's transaction (embedded engine, shared datasource): a rollback
leaves the task open - business data and engine state stay consistent
automatically. Adapter IDs on their OWN datasource (`data-source-name`) run the
completion two-phase through the outbox instead (same rule as workflow starts).
`awarenessOfTask` locates a task by its globally unique execution ID plus a
business-key check. `@TaskEvent CANCELED` IS delivered on Camunda 7: an END
execution listener attached at parse time invokes handlers subscribing to
lifecycle events when the open task's activity is canceled (interrupting
boundary event, instance termination), within the cancellation's transaction.

**User tasks (story 24):** the user task's `camunda:formKey` is the task
definition; a matching `@WorkflowTask` method is an OPTIONAL notification handler
invoked on the engine's global CREATE and DELETE task-listener events (CREATED /
CANCELED via `@TaskEvent`, the task's ID via `@TaskId`) - attached as BUILT-IN
listeners at parse time, so they run before modeller-defined ones. The handler
never completes the task: `ProcessService#completeUserTask` maps to
`TaskService.complete`, `#cancelUserTask` to `TaskService.handleBpmnError`
(error-boundary routing) - within the caller's transaction on shared-datasource
engines, two-phase on separate-datasource adapter ids. `awarenessOfUserTask`
locates a task by its globally unique task ID plus a business-key check.

**Message correlation (story 23):** `correlateMessage` runs entirely within the
caller's transaction (tenant = workflow module, business key = aggregate ID) - a
rollback leaves the instance waiting. A correlation id matches via the V1
local-variable convention `<primary bpmnProcessId>-<messageName>` at the receiving
scope. `startWorkflowByMessage` uses `correlateStartMessage()`. Separate-datasource
adapter ids run both two-phase through the outbox (with waiting-subscription /
already-started pre-checks tolerating stale entries). No variables are ever set -
the payload doctrine.

BPMN expressions like gateway conditions or multi-instance collections
(`${riskAcceptable}`, `${items}`) resolve against the workflow aggregate
identified by the business key (getter, boolean getter or field - Spring beans
remain resolvable on Spring Boot). External tasks (`camunda:topic`) are not
supported yet.

## Keeping workflow modules apart

The [name-clash-avoidance mode](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#how-name-clashes-are-avoided)
decides how a workflow module's identifiers are scoped. `by-adapter` deploys into a
Camunda tenant named after the workflow module (`tenant-id` overrides the name), which is
the Version-1 behaviour; `use-prefix` deploys without a tenant and the adapter rewrites
process ids, `camunda:calledElement` references, message and signal names, escalation and
error codes; `none`, the default of THIS adapter, scopes nothing.

Two decisions worth recording:

- **The default is `none`, although the engine is multi-tenant.** Camunda 7 offers three
  ways of keeping modules apart (a tenant, prefixed identifiers, an engine per module),
  and picking one for the application would presume its operating model. The adapter WARNs
  per workflow module instead, naming all three, until `accept-unscoped-identifiers`
  acknowledges that the identifiers are unique. The acknowledgement is a statement about
  the application, not a log level, which is why it is not simply a logger configuration.
- **Task definitions are NOT prefixed**, unlike on Camunda 8. A Camunda 7 task definition
  is the expression text of the task (`camunda:expression`/`camunda:delegateExpression`)
  respectively the `camunda:formKey`, and it is resolved WITHIN the process by VanillaBP's
  EL resolver. Nothing subscribes to it engine-wide, so there is nothing to clash with.

A tenant id is an ATTRIBUTE of the deployment and of the process definitions, instances
and tasks below it: any name is accepted, no tenant has to exist and none is created.
Registered tenants (`ACT_ID_TENANT`, written by `IdentityService#newTenant`) exist for
tenant memberships and the authorizations built on them, and most applications have none.
Where an application registers tenants but not the one VanillaBP deploys into, the adapter
WARNs, because the deployment works while nobody is authorized for those workflows.

Without a tenant the engine cannot answer which workflow module a running instance belongs
to. The adapter resolves it from the process definition key it registered while wiring,
which keeps everything working that depends on it, including the live evaluation of
workflow-aggregate attributes in BPMN expressions.

## Sharing the workflow aggregate

The engine is embedded, so BPMN expressions are evaluated against the workflow aggregate
ITSELF: the adapter's EL resolver reads it live, and a gateway may use any attribute. The
default of this adapter is therefore that **nothing is shared**, and what an application
does share with `@SyncWithBPMS` is written as Camunda process variables for operator
context in Cockpit only. VanillaBP never reads those variables back.

They are written when the workflow is started and refreshed when an asynchronous task is
completed or canceled, deliberately not after every `@WorkflowTask` method: an expression
never depends on them, so refreshing on the hot path would cost without buying anything.
The one place variables ARE read is `@TaskParam`, which takes the value from the task's
input mapping, a hand-over the model asks for on purpose.

`aggregateChanged(aggregate)` writes the shared values with `setVariables` at the process
instance, `aggregateChanged(aggregate, taskId)` with `setVariablesLocal` at the execution
of the scope the task RUNS in, which the adapter resolves by walking around two scopes:

- the scope Camunda gives an activity of its own where the model asks for one (a task with
  a boundary event attached, one instance of a multi-instance activity), because variables
  written there serve that activity's boundary events and vanish when it ends, and
- the multi-instance BODY, whose variables all instances would share.

This is what makes conditional events usable on Camunda 7: the engine evaluates the
condition of a waiting conditional event when a variable of its scope or of a parent scope
changes, and nothing else. Writing at the scope the task runs in is therefore what reaches
an event subprocess with a conditional start event sitting in that same scope. Where the
application shares nothing at all, a push would carry no values and thus be no change, so
the adapter writes the technical variable `vanillabpAggregateChanged` holding the time of
the push.

Resolving an attribute in an expression is the EL resolver's job, and it answers only for
names the aggregate really carries (`workflowAggregateHasProperty`). Before that check
existed, every name evaluated at a wired task resolved to the task behavior instead of the
aggregate's attribute.

## Signals

`ProcessService.sendSignal(name)` broadcasts through `RuntimeService.createSignalEvent`
inside the caller's transaction, so a rollback takes the broadcast with it. The signal is
scoped like every other identifier of the workflow module: sent for the module's tenant,
or tenant-free where identifiers are prefixed. An engine on its own datasource cannot join
that transaction and broadcasts after the commit through the outbox, like a remote BPMS.

## Workflows the engine starts itself and workflows which ended

A process with a timer, signal or conditional start event runs without anybody calling
`startWorkflow`. The adapter attaches an execution listener to such a start event; it
builds the workflow aggregate and stores the aggregate's ID as the process instance's
business key, which is how this adapter addresses workflows everywhere else. The listener
runs inside the engine's own transaction, so aggregate and process instance commit
together and a failure rolls both back for the engine to retry. Instances started by the
application are skipped, since they already carry a business key. The engine does not tell
a listener the timer's scheduled time, so the aggregate's ID is derived from the moment the
instance is created, which costs nothing when both are written in one transaction.

Where a workflow service declares a `@WorkflowEnded` method, the adapter attaches an END
execution listener to the PROCESS scope, again inside the engine's transaction. Camunda 7
tells the two kinds apart: an execution carrying a delete reason was cancelled, deleted or
terminated (`TERMINATED`), everything else reached an end event (`COMPLETED`, with the id
of that end event). Processes without such a method get no listener.

## Versions of a process

The engine counts a process definition's version upwards per BPMN process id and a running
instance stays on the version it was started with. The adapter reports that version with
every task, user-task event, engine-performed start and workflow end, resolved ONCE per
process definition id and then answered from memory: tasks are delivered inside the
engine's transaction, so a repository query per execution would be paid by every workflow.

A version boundary may also name the model's `camunda:versionTag`. Placing a tag in the
deployment order needs the engine's definition query, which the adapter runs once per
process while the application starts (right after the deployment, so a tag deployed by this
very start is included) and again only for a version it has never seen, which is what a
rolling deployment produces. What the deployment itself reported costs no query at all: the
deploy command names the version the engine assigned to every model, tag included.

## Camunda's web applications

The optional module `camunda7-adapter-spring-boot-webapps` serves Cockpit, Tasklist and
Admin at `/camunda` against the engines this adapter built. They normally arrive with
Camunda's own Spring Boot starter, which brings an engine along, and VanillaBP builds and
owns the engines, so the module does two things:

1. **Camunda's engine auto-configuration is switched off** (`camunda.bpm.enabled` defaults
   to `false` here). It builds a process engine unconditionally, so an application would
   run two engines on one datasource and the second one's job executor would acquire the
   jobs of the first. Setting the property to `true` fails the start with a message saying
   this.
2. **VanillaBP's engines are registered with the runtime container**, because that is where
   the web applications look for engines rather than in the Spring context. When the
   application stops they are removed again.

The web applications are a servlet application built on Spring. There is no Quarkus
equivalent and none is planned, so this module is Spring Boot only.

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

Configuration keys are IDENTICAL to the Spring Boot module (`database-schema-update`,
`history-time-to-live`, `data-source-name`) — `data-source-name` references a named
Quarkus datasource declared under `quarkus.datasource.<name>.*` (on Spring Boot it
references a `DataSource` bean of that name; in both cases the datasource is
application-/runtime-provided, VanillaBP never builds a pool):

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

## Viewing workflows (story 26)

`ProcessService#getProcessDefinitions`, `#getBpmnXml` and `#getWorkflowHistory` are answered
from the embedded engine: `RepositoryService` (every deployed version incl. its BPMN XML) and
`HistoryService` (instance timeline, incidents). Both are cheap local queries - there is
neither an eventual-consistency lag nor an application-version boundary.

- The workflow is addressed by **business key** (aggregate ID) + **tenant** (workflow module);
  the adapter-native process definition id is Camunda's own (`MyProcess:1:8a9c…`), so the exact
  version an instance runs on is reported.
- `getProcessDefinitions` additionally reports the definitions the process' **call activities**
  would call next (latest deployed version of the called process id in the same tenant);
  call activities addressing their process by expression are skipped (only known at runtime).
- The history context of an executed call activity is the **called process instance id**; a
  context not belonging to the workflow is rejected and logged.
- Camunda's fine-grained activity types are mapped onto the SPI's `WorkflowElementType`;
  `error` carries the message of an OPEN incident of that activity.
- **History level matters:** with history level `none` no element history exists - the adapter
  then reports the definition and a `null` element history instead of failing. Ended workflows
  stay viewable until `history-time-to-live` cleanup removes them; afterwards the core raises
  the guiding `WorkflowNotFoundException`.
- Because history is queried, this adapter also reports ENDED workflows as `COMPLETED` to
  VanillaBP's BPMS election (instead of "unknown") - which is what makes viewing ended
  workflows work and keeps a re-dispatched start from starting a second instance of a workflow
  which already ran to its end.

## Known deviations

What this adapter does not deliver, mirrored in one sentence each on the wiki's
[Deviations](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Deviations)
page. An engine on its own datasource is NOT one of them: it is the documented mode
described under [Transaction caveat](#behaviour) above.

### External tasks

A service task wired by `camunda:topic` is not served. The adapter delivers tasks through
the engine's own execution (`camunda:expression`/`camunda:delegateExpression`, see
[Task processing](#task-processing-execution-model)), and the external-task API is a
second delivery mechanism with its own lock, retry and completion model. Nobody asked for
it yet, so there is no timeline.

### `table-prefix`

`vanillabp.adapters.<id>.table-prefix` is meant to let two adapter ids share one datasource
while running separate engines, and it does not start an engine today: with a prefix
configured, `database-schema-update: true` creates no tables and the boot fails on the
engine's own query against the prefixed `ACT_GE_PROPERTY`. The failure comes from a QUERY,
not from a rejected `CREATE`, which points at the engine's schema check rather than at the
DDL. Story 47 verifies what Camunda supports here and then delivers either the schema
creation for prefixed engines or a guiding startup check plus documentation saying so.
Until then, keep several adapter ids apart by their datasource
([Embedded-engine wiring](#embedded-engine-wiring)).

### New jobs wait for the next acquisition cycle

Version 1 woke the job executor up when a transaction creating a job committed
(`WakeupJobExecutor`), so an asynchronous continuation started right away. This adapter
does not, so such a job waits for the executor's regular acquisition cycle. Planned as a
follow-up of the engine-idiomatics story, no date yet.

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

`mvn install verify` builds one aggregated JaCoCo report per platform:

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

Both are published to GitHub Pages by the *Publish to GitHub Packages* workflow on every push to
the default branch. Click the [platform's badge](#documentation-and-supported-platforms) to open
the respective report.

Baseline recorded with the hardening story (2026-07-29): **90.0% line coverage**. The feature
stories' definition of done requires >90% - gaps are filled by the stories touching the respective
code.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
