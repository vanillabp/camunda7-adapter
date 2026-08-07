package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Notifies <code>&#64;WorkflowTask</code> methods about USER-task lifecycle events
 * (story 24): attached as a task listener to user tasks at parse time
 * ({@link Camunda7AsyncBpmnParseListener}) for the engine's global CREATE and
 * DELETE events - CREATE delivers {@link TaskEvent.Event#CREATED} (e.g. to send an
 * email or feed an own task list), DELETE delivers
 * {@link TaskEvent.Event#CANCELED}. The handler is OPTIONAL (a user task without
 * one is simply processed through forms/task lists) and never completes the task
 * on return - completion arrives via <code>ProcessService#completeUserTask</code>.
 * The invocation runs INSIDE the engine's transaction
 * ({@code runInCurrentTransaction}): aggregate changes commit or roll back with
 * the task's creation/cancellation itself.
 */
@Slf4j
public class Camunda7UserTaskEventListener implements TaskListener {

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  public Camunda7UserTaskEventListener(
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskRegistry = taskRegistry;

  }

  @Override
  public void notify(
      final DelegateTask delegateTask) {

    final var execution = (ExecutionEntity) delegateTask.getExecution();
    final var processDefinition = execution.getProcessDefinition();
    // story 35: the tenant answers the workflow module only while the module IS
    // isolated by one - with prefixed identifiers the registry knows which module a
    // process definition key belongs to, and what its plain id is
    final var scopedBpmnProcessId = processDefinition.getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(processDefinition.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);

    final var connectable = taskRegistry
        .resolve(
            workflowModuleId,
            scopedBpmnProcessId,
            delegateTask.getTaskDefinitionKey(),
            null)
        .filter(candidate -> candidate.type() == Camunda7TaskConnectable.Type.USER_TASK);
    if (connectable.isEmpty()) {
      // not a VanillaBP-wired user task of this engine
      return;
    }

    final var event = TaskListener.EVENTNAME_DELETE.equals(delegateTask.getEventName())
        ? TaskEvent.Event.CANCELED
        : TaskEvent.Event.CREATED;

    log.debug(
        "Camunda7: delivering {} for user task '{}' (activity '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        event,
        delegateTask.getId(),
        delegateTask.getTaskDefinitionKey(),
        bpmnProcessId,
        workflowModuleId);

    // user-task handlers are OPTIONAL by design - skip silently without one
    final var context = new Camunda7UserTaskInvocationContext(connectable.get(), delegateTask, event);
    if (!workflowTaskInvoker.workflowTaskHandlerExists(
        workflowModuleId, bpmnProcessId, context.getTaskDefinition())) {
      log.trace(
          "Camunda7: no @WorkflowTask handler for user task '{}' of BPMN process '{}' - skipping "
              + "the {} notification",
          delegateTask.getTaskDefinitionKey(),
          bpmnProcessId,
          event);
      return;
    }
    // the core's event filter skips methods not subscribing to the event
    final WorkflowTaskOutcome outcome = workflowTaskInvoker.invokeWorkflowTask(
        workflowModuleId,
        bpmnProcessId,
        context);

    if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
      // a TaskException in a user-task NOTIFICATION handler is a defect: the task
      // was just created/canceled - there is nothing to complete by BPMN error
      throw new IllegalStateException(
          ("The @WorkflowTask method notified about the %s event of user task '%s' (BPMN process "
              + "'%s' of workflow module '%s') threw a TaskException! User-task notification "
              + "handlers must not raise BPMN errors - route errors via "
              + "ProcessService#cancelUserTask instead.")
              .formatted(event, delegateTask.getTaskDefinitionKey(), bpmnProcessId, workflowModuleId));
    }

  }

  /**
   * The neutral invocation context built from a Camunda 7 user-task event.
   */
  static class Camunda7UserTaskInvocationContext implements TaskInvocationContext {

    private final Camunda7TaskConnectable connectable;

    private final DelegateTask delegateTask;

    private final TaskEvent.Event event;

    Camunda7UserTaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateTask delegateTask,
        final TaskEvent.Event event) {

      this.connectable = connectable;
      this.delegateTask = delegateTask;
      this.event = event;

    }

    @Override
    public String getTaskDefinition() {

      return connectable.taskDefinition() != null
          ? connectable.taskDefinition()
          : connectable.elementId();

    }

    @Override
    public String getWorkflowAggregateId() {

      return delegateTask.getExecution().getBusinessKey();

    }

    @Override
    public String getTaskId() {

      // the engine's task ID (ACT_RU_TASK) - used by
      // ProcessService#completeUserTask/#cancelUserTask
      return delegateTask.getId();

    }

    @Override
    public TaskEvent.Event getTaskEvent() {

      return event;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      return delegateTask.getVariableLocal(name);

    }

    @Override
    public boolean runInCurrentTransaction() {

      return true;

    }

  }

}
