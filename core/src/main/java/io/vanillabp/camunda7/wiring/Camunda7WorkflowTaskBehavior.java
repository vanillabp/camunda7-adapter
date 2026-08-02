package io.vanillabp.camunda7.wiring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Executes a <code>&#64;WorkflowTask</code> method for one BPMN task, dispatched
 * through the core's {@link WorkflowTaskInvoker}: the invocation context is built
 * from the {@link DelegateExecution} (business key = serialized aggregate ID,
 * local variables for <code>&#64;TaskParam</code>, multi-instance context from the
 * execution hierarchy) and the handler runs INSIDE the engine's transaction
 * ({@link TaskInvocationContext#runInCurrentTransaction()}): business changes and
 * engine state commit or roll back together.
 * <p>
 * Outcome mapping:
 * <ul>
 * <li>COMPLETED - the activity is left (task completes);</li>
 * <li>COMPLETION_PENDING (<code>&#64;TaskId</code> methods) - the activity is NOT
 * left: the execution stays at the task until it is completed asynchronously via
 * <code>ProcessService#completeTask</code> (which signals the execution);</li>
 * <li>BPMN_ERROR ({@code TaskException}) - a {@link BpmnError} with the
 * exception's error code is thrown for error-boundary routing; the aggregate
 * changes were saved and commit with the engine's transaction;</li>
 * <li>any other exception - propagates: the engine rolls back the job transaction
 * (business changes included) and applies its retry semantics.</li>
 * </ul>
 * Used for <code>camunda:delegateExpression</code> tasks (as an
 * {@link AbstractBpmnActivityBehavior}, so the activity can stay open) and for
 * <code>camunda:expression</code> tasks (executed inline by the
 * {@link Camunda7TaskELResolver}; staying open is impossible there, so
 * <code>&#64;TaskId</code> methods require a delegate expression).
 */
public class Camunda7WorkflowTaskBehavior extends AbstractBpmnActivityBehavior {

  private final Camunda7TaskConnectable connectable;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  public Camunda7WorkflowTaskBehavior(
      final Camunda7TaskConnectable connectable,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this.connectable = connectable;
    this.workflowTaskInvoker = workflowTaskInvoker;

  }

  @Override
  public void execute(
      final ActivityExecution execution) throws Exception {

    final var outcome = invokeHandler(execution);
    if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETION_PENDING) {
      // @TaskId method: the task stays open, completion arrives via
      // ProcessService#completeTask which signals this execution
      return;
    }
    leave(execution);

  }

  @Override
  public void signal(
      final ActivityExecution execution,
      final String signalName,
      final Object signalData) throws Exception {

    // asynchronous completion (ProcessService#completeTask, upcoming story)
    leave(execution);

  }

  /**
   * Runs the handler for the given execution and maps a
   * {@code TaskException} outcome to a {@link BpmnError}. Shared by the
   * delegate-expression path (this behavior) and the expression path (the EL
   * resolver).
   *
   * @param execution The current execution
   * @return The outcome (never BPMN_ERROR - that one is thrown)
   */
  WorkflowTaskOutcome invokeHandler(
      final DelegateExecution execution) {

    final var outcome = workflowTaskInvoker.invokeWorkflowTask(
        connectable.workflowModuleId(),
        connectable.bpmnProcessId(),
        new Camunda7TaskInvocationContext(connectable, execution));
    if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
      // error-boundary routing; the aggregate changes were saved and commit
      // with the engine's transaction (the V1 contract)
      throw outcome.errorName() != null
          ? new BpmnError(outcome.errorCode(), outcome.errorName())
          : new BpmnError(outcome.errorCode());
    }
    return outcome;

  }

  /**
   * The neutral invocation context built from a Camunda 7 execution.
   */
  static class Camunda7TaskInvocationContext implements TaskInvocationContext {

    private final Camunda7TaskConnectable connectable;

    private final DelegateExecution execution;

    private Map<String, MultiInstanceValue> multiInstances;

    Camunda7TaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateExecution execution) {

      this.connectable = connectable;
      this.execution = execution;

    }

    @Override
    public String getTaskDefinition() {

      return connectable.taskDefinition();

    }

    @Override
    public String getWorkflowAggregateId() {

      return execution.getBusinessKey();

    }

    @Override
    public String getTaskId() {

      // the execution's ID identifies the open task instance - used by
      // ProcessService#completeTask to signal the parked execution
      return execution.getId();

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      return execution.getVariableLocal(name);

    }

    @Override
    public boolean runInCurrentTransaction() {

      // the embedded engine invokes handlers inside its own (job) transaction -
      // business changes and engine state commit or roll back together
      return true;

    }

    @Override
    public Map<String, MultiInstanceValue> getMultiInstances() {

      if (multiInstances == null) {
        multiInstances = determineMultiInstances(execution);
      }
      return multiInstances;

    }

  }

  /**
   * Collects the multi-instance context(s) the task executes in by walking the
   * execution hierarchy from the current element up to the process root
   * (Version-1 algorithm): for every multi-instance activity found, Camunda's
   * <code>loopCounter</code>/<code>nrOfInstances</code> variables and the
   * configured element variable are read from the corresponding execution.
   */
  static Map<String, MultiInstanceValue> determineMultiInstances(
      final DelegateExecution execution) {

    final var result = new LinkedHashMap<String, MultiInstanceValue>();

    final var model = execution.getBpmnModelElementInstance().getModelInstance();

    DelegateExecution miExecution = execution;
    while (miExecution != null) {

      final var bpmnElement = getCurrentElement(model, miExecution);
      if (bpmnElement instanceof Activity activity && (activity
          .getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loopCharacteristics)) {
        final var itemNo = (Integer) miExecution.getVariable("loopCounter");
        final var totalCount = (Integer) miExecution.getVariable("nrOfInstances");
        final var elementVariable = loopCharacteristics.getCamundaElementVariable();
        final var currentItem = elementVariable == null
            ? null
            : miExecution.getVariable(elementVariable);
        if ((itemNo != null) && (totalCount != null)) {
          result.put(
              ((BaseElement) bpmnElement).getId(),
              new MultiInstanceValue(currentItem, itemNo, totalCount));
        }
      }

      miExecution = miExecution.getParentId() != null
          ? ((ExecutionEntity) miExecution).getParent()
          : miExecution.getSuperExecution();

    }

    // the walk collects innermost first - the SPI contract is outermost first
    final var outermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    result
        .reversed()
        .forEach(outermostFirst::put);
    return outermostFirst;

  }

  private static ModelElementInstance getCurrentElement(
      final ModelInstance model,
      final DelegateExecution execution) {

    if (execution.getBpmnModelElementInstance() != null) {
      return execution.getBpmnModelElementInstance();
    }

    // executions of activities (e.g. embedded subprocesses) encode the element in
    // the activity-instance ID: "[element-id]:[instance-id]"
    final var activityInstanceId = execution.getActivityInstanceId();
    if (activityInstanceId == null) {
      return null;
    }
    final var elementMarker = activityInstanceId.indexOf(':');
    if (elementMarker == -1) {
      return null;
    }
    return model.getModelElementById(activityInstanceId.substring(0, elementMarker));

  }

}
