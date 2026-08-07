# camunda7-adapter (core)

Contributor documentation for the platform-neutral core of the VanillaBP Camunda 7
adapter. User-facing documentation lives in
[this adapter's wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki); the repository
root `README.md` documents the repository for contributors.

## What lives here

Plain Java — **no Spring or Quarkus imports**. This module implements the VanillaBP
adapter SPI (`io.vanillabp.adapter:migration-adapter-spi`) against the Camunda 7 engine
(`org.camunda.bpm:camunda-engine`, which also provides
`org.camunda.bpm.model:camunda-bpmn-model`). Everything BPMS-specific belongs here; the
`spring-boot` module only constructs and registers these objects.

|                                 Type                                 |                                                               Role                                                                |
|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `Camunda7ProcessingContext`                                          | The `PC` accumulator threaded through the deployment pipeline: workflow module id, the resources to deploy and the tasks to wire. |
| `Camunda7DeploymentService`                                          | `AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext>`, one per configured adapter id.                          |
| `Camunda7ProcessService<A>`                                          | `MigratableProcessService<A>`, the per-adapter runtime the core migration adapter delegates to.                                   |
| `Camunda7WorkflowViewer`                                             | The viewer/history API against `RepositoryService`/`HistoryService` (definitions, BPMN XML, element history).                     |
| `Camunda7TaskRegistry` + `Camunda7TaskConnectable`                   | What the deployment wiring collected: which BPMN element is served by which `@WorkflowTask`.                                      |
| `Camunda7WorkflowTaskBehavior`                                       | Executes a task through the core's `WorkflowTaskInvoker` and maps the outcome (leave activity / stay open / `BpmnError`).         |
| `Camunda7TaskELResolver` + `Camunda7TaskExpressionManager`           | Make BPMN expressions read the LIVE workflow aggregate and dispatch `camunda:expression` tasks.                                   |
| `Camunda7AsyncBpmnParseListener`                                     | Forces `asyncBefore`/`asyncAfter` onto service-like tasks so every task runs in its own transaction.                              |
| `Camunda7TaskCancellationListener` + `Camunda7UserTaskEventListener` | Deliver `@TaskEvent` CREATED/CANCELED for service and user tasks.                                                                 |
| `Camunda7JobExecutorLifecycle`                                       | Starts the engine-global job executor with the first, stops it with the last workflow module.                                     |
| `Camunda7InstanceIdentity`                                           | Decides whether two adapter ids of this type are distinguishable (datasource or `table-prefix`).                                  |

## Adapter type vs. id

The adapter **type** is the constant `"camunda7"`. Adapter **ids** come from
configuration; the same type may be configured under several ids (e.g. two Camunda 7
engines side by side during a migration), so both the deployment service and the process
service exist per id, not per type.

## Camunda 7 traits reflected here

Camunda 7 is embedded and joins the application's local transaction. Hence
`Camunda7ProcessService.needsTwoPhaseCommitForStartingWorkflows()` returns `false`:
starting a workflow happens completely in phase one; phase two is a no-op and the
transaction outbox is not involved. Workflow-module isolation maps the workflow
module id onto the Camunda **tenant id**, and the 1:1 aggregate relation onto the Camunda
**business key**.

## What is implemented

The adapter SPI is served completely: the deployment pipeline (`readBpmn` …
`startWorkflowProcessing`), workflow start, task processing incl. `@TaskId` tasks and
`@TaskEvent` deliveries, user tasks, message correlation and start-by-message, the
awareness probes used by the BPMS election, and the viewer/history API.

Two deliberate exceptions exist, and both fail LOUDLY rather than silently:

- **external tasks (`camunda:topic`)** are not wired — Camunda 7's external-task pattern
  has no VanillaBP counterpart yet;
- `cancelUserTask` by BPMN error works here (unlike Camunda 8), so nothing is stubbed
  for it.

A method that cannot do its job throws with a message naming the reason; a silent no-op
would hide wiring bugs.
