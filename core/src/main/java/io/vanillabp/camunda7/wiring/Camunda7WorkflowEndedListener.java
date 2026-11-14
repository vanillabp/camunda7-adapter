package io.vanillabp.camunda7.wiring;

import java.time.Instant;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * Tells the application that a workflow ended. It is attached as an END execution listener
 * to the PROCESS scope of processes whose workflow service declares a
 * <code>&#64;WorkflowEnded</code> method (see {@link Camunda7AsyncBpmnParseListener}).
 * <p>
 * It runs inside the engine's own transaction. On the application's datasource, the
 * application's change to the workflow aggregate commits together with the end of the process
 * instance, and it rolls back with it. An engine on a datasource of its own cannot share that
 * transaction. Then VanillaBP opens a transaction of its own for the notification, and the
 * notification is delivered at least once.
 * <p>
 * What the engine reports decides the KIND. A workflow is cancelled where the instance ends
 * without standing at an element of its own model. That is the case when somebody deletes
 * the instance, with a reason or without one, and when a modification takes its last token
 * away. The engine's history calls such an instance terminated, and it decides that by the
 * same question. A terminate end event, an interrupting event subprocess and an error which
 * nobody catches end the instance at an element of the model, so they are a completion.
 * <p>
 * Why a listener is added to the deployed model, and only where a handler exists, is decision 5 in
 * the repository's DECISIONS.md.
 */
public class Camunda7WorkflowEndedListener implements ExecutionListener {

  private static final Logger log = LoggerFactory.getLogger(Camunda7WorkflowEndedListener.class);

  private final WorkflowEndedInvoker workflowEndedInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * One listener for the whole engine. It is attached to the process scope of every model
   * whose application has a method for the end of a workflow.
   *
   * @param workflowEndedInvoker Where the core is told that a workflow ended
   * @param taskRegistry What the workflow module of the ended process is looked up in
   */
  public Camunda7WorkflowEndedListener(
      final WorkflowEndedInvoker workflowEndedInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this.workflowEndedInvoker = workflowEndedInvoker;
    this.taskRegistry = taskRegistry;

  }

  @Override
  public void notify(
      final DelegateExecution execution) {

    final var businessKey = execution.getProcessBusinessKey();
    if ((businessKey == null) || businessKey.isBlank()) {
      // not a VanillaBP workflow. Without a business key there is no workflow
      // aggregate this end could be reported for
      return;
    }

    final var processDefinitionKey = execution.getProcessEngineServices()
        .getRepositoryService()
        .getProcessDefinition(execution.getProcessDefinitionId())
        .getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(execution.getTenantId(), processDefinitionKey);
    if (workflowModuleId == null) {
      log
          .debug(
              "Camunda7: no workflow module known for process definition '{}' - the end of instance "
                  + "'{}' is not reported",
              processDefinitionKey,
              execution.getProcessInstanceId());
      return;
    }
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey);

    final var kind = endsWithoutAnElementOfTheModel(execution)
        ? WorkflowEnd.Kind.CANCELED
        : WorkflowEnd.Kind.COMPLETED;
    // the activity the process instance ends at. It is the end event for a regular end,
    // and nothing where the instance was cancelled
    final var endEventId = kind == WorkflowEnd.Kind.COMPLETED
        ? execution.getCurrentActivityId()
        : null;
    final var processVersion = taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());

    workflowEndedInvoker
        .workflowEnded(
            workflowModuleId,
            bpmnProcessId,
            new WorkflowEndedContext() {

              @Override
              public String getAdapterId() {
                return taskRegistry.getAdapterId();
              }

              @Override
              public String getWorkflowAggregateId() {
                return businessKey;
              }

              @Override
              public WorkflowEnd.Kind getKind() {
                return kind;
              }

              @Override
              public Instant getEndTime() {
                // the engine does not hand the listener an end time. This is the
                // moment the instance ends, because it is the same transaction
                return Instant.now();
              }

              @Override
              public String getEndEventId() {
                return endEventId;
              }

              @Override
              public String getProcessVersion() {
                return processVersion;
              }

              @Override
              public boolean runInCurrentTransaction() {
                // an embedded engine. The notification belongs into the transaction
                // which ends the workflow. An engine on a datasource of its own ends it
                // where the application's persistence cannot join. So VanillaBP opens
                // its own transaction, and the notification is at-least-once like every
                // other delivery of that mode
                return !taskRegistry.engineRunsOnItsOwnDataSource();
              }

            });

  }

  /**
   * Whether the instance ends without standing at an element of its own model. Where the engine
   * tears an instance down, it walks out of every scope and leaves the process instance without
   * an activity. The history of the engine asks the same question to tell a terminated instance
   * from a completed one. A delete reason is not enough: the engine only has one where the caller
   * gave one, and the REST API and the web app give none.
   *
   * @param execution The process instance which ends
   * @return <code>true</code> where the instance was torn down instead of reaching an element
   */
  private static boolean endsWithoutAnElementOfTheModel(
      final DelegateExecution execution) {

    if (!(execution instanceof PvmExecutionImpl pvmExecution)) {
      return false;
    }
    return (pvmExecution.getDeleteReason() != null) || (pvmExecution.getActivity() == null);

  }

}
