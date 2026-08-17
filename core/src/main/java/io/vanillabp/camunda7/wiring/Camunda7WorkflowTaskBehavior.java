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

  /**
   * Translates the error code of a {@code TaskException} into what the engine knows
   * (story 35: the model's error codes are prefixed too). May be <code>null</code>.
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  private final String adapterId;

  /**
   * Answers the version of the process definition an execution runs on (story 48).
   * May be <code>null</code> (tests): no version is reported then.
   */
  private final Camunda7TaskRegistry taskRegistry;

  public Camunda7WorkflowTaskBehavior(
      final Camunda7TaskConnectable connectable,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this(connectable, workflowTaskInvoker, null, null, null);

  }

  public Camunda7WorkflowTaskBehavior(
      final Camunda7TaskConnectable connectable,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final String adapterId,
      final Camunda7TaskRegistry taskRegistry) {

    this.connectable = connectable;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.scoping = scoping;
    this.adapterId = adapterId;
    this.taskRegistry = taskRegistry;

  }

  /**
   * The BPMN error code as the engine knows it.
   */
  private String scopedErrorCode(
      final String errorCode) {

    return (scoping == null) || (adapterId == null)
        ? errorCode
        : scoping.scopedIdentifier(connectable.workflowModuleId(), errorCode, adapterId);

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

  /**
   * The signal name carrying a cancellation
   * ({@code ProcessService#cancelTask}): the signal data is the BPMN error code to
   * propagate.
   */
  public static final String SIGNAL_CANCEL = "vanillabp:cancel";

  @Override
  public void signal(
      final ActivityExecution execution,
      final String signalName,
      final Object signalData) throws Exception {

    // asynchronous completion/cancellation (ProcessService#completeTask/cancelTask
    // signals the parked execution - see Camunda7ProcessService)
    if (SIGNAL_CANCEL.equals(signalName)) {
      // cancelTask: route the workflow through an error boundary event; thrown
      // BpmnErrors are not translated automatically on the signal path, so the
      // error is propagated explicitly
      org.camunda.bpm.engine.impl.bpmn.helper.BpmnExceptionHandler.propagateBpmnError(
          new BpmnError(String.valueOf(signalData)),
          execution);
      return;
    }
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
        new Camunda7TaskInvocationContext(connectable, execution, taskRegistry));
    // story 66: what the handler computed has to reach the engine BEFORE it evaluates
    // what comes next - a gateway right behind this task would otherwise decide on the
    // values of the last ProcessService call. Written here, inside the engine's
    // transaction, so the variables commit with the aggregate and with the token
    writeSharedValues(execution);
    if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
      // error-boundary routing; the aggregate changes were saved and commit
      // with the engine's transaction (the V1 contract). The values are written above,
      // because the flow behind the error boundary may branch on them as well
      throw outcome.errorName() != null
          ? new BpmnError(scopedErrorCode(outcome.errorCode()), outcome.errorName())
          : new BpmnError(scopedErrorCode(outcome.errorCode()));
    }
    return outcome;

  }

  /**
   * Writes the values the aggregate shares with the BPMS onto the execution (story 66).
   * <p>
   * The values are read in the CALLER's transaction, which is the engine's: the handler
   * just changed the aggregate there, and reading in a new transaction would either see
   * the state before the handler or wait for the row this transaction holds. The read
   * never throws, and where nothing is shared nothing is written.
   *
   * @param execution The execution of the task just processed
   */
  private void writeSharedValues(
      final DelegateExecution execution) {

    final var businessKey = execution.getProcessBusinessKey();
    if (businessKey == null) {
      // no aggregate identity at hand (a process started outside VanillaBP): there is
      // nothing to read the values from
      return;
    }
    final var sharedValues = workflowTaskInvoker.syncedWorkflowAggregateValuesInCurrentTransaction(
        connectable.workflowModuleId(),
        connectable.bpmnProcessId(),
        businessKey,
        io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE);
    if (sharedValues.isEmpty()) {
      return;
    }
    execution
        .setVariables(
            io.vanillabp.camunda7.sync.Camunda7Variables
                .of(
                    sharedValues,
                    taskRegistry.serializationFormatFor(
                        connectable.workflowModuleId(),
                        connectable.bpmnProcessId())));

  }

  /**
   * The neutral invocation context built from a Camunda 7 execution.
   */
  static class Camunda7TaskInvocationContext implements TaskInvocationContext {

    private final Camunda7TaskConnectable connectable;

    private final DelegateExecution execution;

    private final io.vanillabp.spi.service.TaskEvent.Event taskEvent;

    /**
     * Answers the version of the execution's process definition. May be
     * <code>null</code>: no version is reported then, which matches every method.
     */
    private final Camunda7TaskRegistry taskRegistry;

    private Map<String, MultiInstanceValue> multiInstances;

    @Override
    public String getAdapterId() {

      return taskRegistry == null
          ? null
          : taskRegistry.getAdapterId();

    }

    Camunda7TaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateExecution execution,
        final Camunda7TaskRegistry taskRegistry) {

      this(connectable, execution, io.vanillabp.spi.service.TaskEvent.Event.CREATED, taskRegistry);

    }

    Camunda7TaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateExecution execution,
        final io.vanillabp.spi.service.TaskEvent.Event taskEvent,
        final Camunda7TaskRegistry taskRegistry) {

      this.connectable = connectable;
      this.execution = execution;
      this.taskEvent = taskEvent;
      this.taskRegistry = taskRegistry;

    }

    @Override
    public String getProcessVersion() {

      // resolved once per process definition id and then answered from memory - a
      // query per task execution would be paid by every workflow
      return taskRegistry == null
          ? null
          : taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());

    }

    @Override
    public io.vanillabp.spi.service.TaskEvent.Event getTaskEvent() {

      return taskEvent;

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
