# camunda7-adapter (core)

Contributor documentation for the platform-neutral core of the VanillaBP Camunda 7
adapter. (User-facing documentation lives in the repository root `README.md`.)

## What lives here

Plain Java — **no Spring or Quarkus imports**. This module implements the VanillaBP
adapter SPI (`io.vanillabp.adapter:migration-adapter-spi`) against the Camunda 7 engine
(`org.camunda.bpm:camunda-engine`, which also provides
`org.camunda.bpm.model:camunda-bpmn-model`). Everything BPMS-specific belongs here; the
`spring-boot` module only constructs and registers these objects.

|            Type             |                                                                  Role                                                                   |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `Camunda7ProcessingContext` | The `PC` accumulator threaded through the deployment pipeline (holds the workflow module id for now; will collect resources to deploy). |
| `Camunda7DeploymentService` | `AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext>`, one per configured adapter id.                                |
| `Camunda7ProcessService<A>` | `MigratableProcessService<A>`, the per-adapter runtime the core migration adapter delegates to.                                         |

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

## Not-yet-implemented methods

The deployment pipeline (`readBpmn` … `startWorkflowProcessing`) and the workflow
start (`startWorkflowPhaseOne`; phase two is a no-op for the embedded engine) are
implemented. Methods of upcoming stories (currently `awarenessOfTask`/
`awarenessOfWorkflow`) throw
`UnsupportedOperationException("<method> is implemented in a later story")` - they
must never silently do nothing, a silent no-op would hide wiring bugs of later
stories.
