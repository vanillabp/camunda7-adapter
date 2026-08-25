package io.vanillabp.camunda7.wiring;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Attached to the start events the engine fires on its own (timer, signal,
 * conditional - see {@link Camunda7AsyncBpmnParseListener}), this listener gives the
 * new workflow its workflow aggregate: it asks the core to build one and stores the
 * aggregate's ID as the instance's BUSINESS KEY, which is how everything else in
 * this adapter finds a workflow again.
 * <p>
 * It runs inside the engine's own transaction (the timer job's, respectively the
 * command's), so aggregate and process instance commit together, and it is skipped
 * for instances which already carry a business key: those were started by the
 * application through {@code ProcessService}, and their aggregate exists.
 * <p>
 * Why a listener is added to the deployed model, and only where a handler exists, is decision 5 in
 * the repository's DECISIONS.md.
 */
public class Camunda7BpmsInitiatedStartListener implements ExecutionListener {

  private static final Logger log = LoggerFactory
      .getLogger(Camunda7BpmsInitiatedStartListener.class);

  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Which kind of start event this listener sits on, decided at parse time.
   */
  private final BpmsStartTrigger.Kind kind;

  public Camunda7BpmsInitiatedStartListener(
      final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final Camunda7TaskRegistry taskRegistry,
      final BpmsStartTrigger.Kind kind) {

    this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;
    this.taskRegistry = taskRegistry;
    this.kind = kind;

  }

  @Override
  public void notify(
      final DelegateExecution execution) {

    if ((execution.getProcessBusinessKey() != null) && !execution.getProcessBusinessKey().isBlank()) {
      // started by the application: ProcessService set the business key from the
      // aggregate it was handed, so there is nothing to build here
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
              "Camunda7: no workflow module known for process definition '{}' - the start of instance "
                  + "'{}' is not a VanillaBP workflow",
              processDefinitionKey,
              execution.getProcessInstanceId());
      return;
    }
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey);

    final var signalName = taskRegistry
        .signalNameOfStartEvent(workflowModuleId, processDefinitionKey, execution.getCurrentActivityId());
    final var processVersion = taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());
    final var result = bpmsInitiatedStartInvoker
        .startWorkflowByBpms(
            workflowModuleId,
            bpmnProcessId,
            contextOf(execution, signalName, processVersion));

    // the aggregate's ID is this adapter's handle on the workflow (business key) -
    // set within the same transaction which created the instance
    ((PvmExecutionImpl) execution).setProcessBusinessKey(result.workflowAggregateId());

    log
        .debug(
            "Camunda7: the BPMS started '{}' of workflow module '{}' by start event '{}' - workflow "
                + "aggregate '{}' {}",
            bpmnProcessId,
            workflowModuleId,
            execution.getCurrentActivityId(),
            result.workflowAggregateId(),
            result.created()
                ? "created"
                : "existed already");

  }

  private BpmsInitiatedStartContext contextOf(
      final DelegateExecution execution,
      final String signalName,
      final String processVersion) {

    // what the model set before the start event completed: expressions, input
    // mappings, and for a signal the payload the broadcast carried
    final Map<String, Object> variables = new HashMap<>(execution.getVariables());
    final var startEventId = execution.getCurrentActivityId();

    return new BpmsInitiatedStartContext() {

      @Override
      public String getAdapterId() {
        return taskRegistry.getAdapterId();
      }

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

      @Override
      public Instant getTriggerTime() {
        // the engine does not hand a listener the timer's scheduled time, so this
        // is the moment the instance is created. Nothing is lost by that: this
        // listener runs in the transaction which creates the process instance, so a
        // failed attempt takes the aggregate with it and a retry starts from
        // nothing. The repetition guard of the core matters where a BPMS reports a
        // start it already committed - which cannot happen on an embedded engine.
        return Instant.now();
      }

      @Override
      public String getSignalName() {
        return signalName;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

      @Override
      public String getNativeInstanceId() {
        return execution.getProcessInstanceId();
      }

      @Override
      public boolean runInCurrentTransaction() {
        // an embedded engine sharing the application's transaction: the aggregate
        // has to be written in the transaction which creates the instance
        return true;
      }

    };

  }

}
