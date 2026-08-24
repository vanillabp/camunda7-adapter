package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers {@link TaskEvent.Event#CANCELED} to <code>&#64;WorkflowTask</code>
 * methods subscribing to it: attached as an END execution listener to service-like
 * activities at parse time ({@link Camunda7AsyncBpmnParseListener}), it invokes the
 * handler when the activity ends by CANCELLATION (interrupting boundary event,
 * process termination) - a normal completion does not fire it. The core skips
 * methods without a matching <code>&#64;TaskEvent</code> parameter filter, so
 * handlers not asking for lifecycle events never see the delivery.
 * <p>
 * The invocation runs INSIDE the engine's cancellation transaction
 * ({@code runInCurrentTransaction}): aggregate changes made by the handler commit
 * or roll back together with the cancellation itself.
 */
@Slf4j
public class Camunda7TaskCancellationListener implements ExecutionListener {

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  public Camunda7TaskCancellationListener(
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskRegistry = taskRegistry;

  }

  @Override
  public void notify(
      final DelegateExecution execution) throws Exception {

    if (!(execution instanceof ExecutionEntity executionEntity) || !executionEntity.isCanceled()) {
      // a normal completion - the regular invocation already happened
      return;
    }

    final var processDefinition = executionEntity.getProcessDefinition();
    // The tenant answers the workflow module only while the module IS
    // isolated by one - with prefixed identifiers the registry knows which module a
    // process definition key belongs to, and what its plain id is
    final var scopedBpmnProcessId = processDefinition.getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(processDefinition.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);

    final var connectable = taskRegistry.resolve(
        workflowModuleId,
        bpmnProcessId,
        execution.getCurrentActivityId(),
        null);
    if (connectable.isEmpty()) {
      // not a VanillaBP-wired task of this engine
      return;
    }

    log.debug(
        "Camunda7: delivering CANCELED for task '{}' (activity '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        execution.getId(),
        execution.getCurrentActivityId(),
        bpmnProcessId,
        workflowModuleId);

    // outcome deliberately ignored: there is nothing to complete - the activity is
    // being canceled; the core skips methods not subscribing to CANCELED
    workflowTaskInvoker.invokeWorkflowTask(
        workflowModuleId,
        bpmnProcessId,
        new Camunda7WorkflowTaskBehavior.Camunda7TaskInvocationContext(
            connectable.get(), execution, TaskEvent.Event.CANCELED, taskRegistry));

  }

}
