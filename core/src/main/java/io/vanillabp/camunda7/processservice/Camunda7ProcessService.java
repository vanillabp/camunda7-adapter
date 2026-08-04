package io.vanillabp.camunda7.processservice;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 7 implementation of the VanillaBP {@link MigratableProcessService}. One
 * instance exists per configured adapter id.
 * <p>
 * Camunda 7 runs embedded in the application's JVM and - by default - shares the same
 * database and the same transaction as the business code. Therefore
 * {@link #needsTwoPhaseCommitForStartingWorkflows()} returns {@code false}: starting a
 * workflow happens completely in phase one within the local transaction; phase two is a
 * no-op and no outbox is involved.
 * <p>
 * <b>Exception - adapter ids with their OWN datasource</b>
 * (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>, the engine-side-by-side
 * migration scenario): such an engine cannot join the caller's transaction (its engine
 * commands commit on their own transaction manager), so a phase-one start would leave a
 * ghost process instance if the caller's transaction rolls back afterwards. These
 * adapter ids therefore use VanillaBP's regular two-phase start
 * ({@link #needsTwoPhaseCommitForStartingWorkflows()} = {@code true}): phase one does
 * nothing against the engine, phase two (after commit, dispatched via the phase-two
 * outbox) starts the instance idempotently (skipped if a RUNNING instance with the same
 * business key/tenant already exists; like every outbox-based operation this keeps an
 * at-least-once residual window, e.g. if the first instance already completed).
 * <p>
 * The workflow-aggregate ID maps naturally onto the Camunda 7 <b>business key</b> and the
 * workflow module ID onto the Camunda <b>tenant ID</b> - see
 * {@link #startProcessInstance(String, String, Object)}.
 */
@Slf4j
@RequiredArgsConstructor
public class Camunda7ProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  /**
   * The embedded engine's runtime service used to start process instances. Provided by
   * the platform module (Spring Boot) which wires the embedded engine sharing the
   * application's data source and transaction manager.
   */
  private final RuntimeService runtimeService;

  /**
   * Whether this adapter id's engine runs on its OWN datasource (see class comment):
   * engine commands then do not join the caller's transaction and starting workflows
   * uses the two-phase pattern.
   */
  private final boolean usesSeparateDataSource;

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    // Sharing the application's datasource (the default), Camunda 7 joins the local
    // transaction - everything happens in phase one, no two-phase commit / outbox
    // required. With an OWN datasource the engine cannot join the caller's
    // transaction, so the two-phase pattern prevents ghost instances (see class
    // comment).
    return usesSeparateDataSource;

  }

  /**
   * Starts a Camunda 7 process instance inside the caller's transaction. The
   * <b>business key</b> is the workflow-aggregate ID as a string, the <b>tenant ID</b> is
   * the workflow module ID (matching how {@link Camunda7DeploymentService} deployed the
   * process). Because the embedded engine shares the application's data source and
   * transaction, the created instance is committed or rolled back together with the
   * business data.
   * <p>
   * The service task following the (asynchronous) start event is not executed
   * synchronously: the async-before continuation parks the instance in the job executor,
   * so no {@code @WorkflowTask} wiring is required for the start to succeed.
   *
   * @param workflowModuleId The workflow module ID (used as the Camunda tenant ID)
   * @param bpmnProcessId The BPMN process ID to start
   * @param workflowAggregateId The workflow-aggregate ID (used as the business key)
   * @return The started process instance
   */
  public ProcessInstance startProcessInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    // the aggregate id was validated (non-null, non-blank) once in the core's
    // MigrationProcessService before phase one is invoked
    final var businessKey = String.valueOf(workflowAggregateId);

    final var processInstance = runtimeService
        .createProcessInstanceByKey(bpmnProcessId)
        .processDefinitionTenantId(workflowModuleId)
        .businessKey(businessKey)
        .execute();

    log.info(
        "Camunda7[{}]: started workflow '{}' (tenant '{}', business key '{}') as process instance '{}'",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        businessKey,
        processInstance.getId());

    return processInstance;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final Object workflowAggregateId,
      final String taskId) {

    // the task ID of a Camunda 7 @TaskId handler is the parked execution's ID -
    // globally unique within the engine, so no tenant/process scoping is needed
    // (the business key is verified so a foreign engine's execution ID can never
    // match a different workflow)
    try {
      final var execution = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .singleResult();
      if (execution == null) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      final var processInstance = runtimeService
          .createProcessInstanceQuery()
          .processInstanceId(execution.getProcessInstanceId())
          .singleResult();
      if ((processInstance == null) || !String.valueOf(workflowAggregateId).equals(processInstance.getBusinessKey())) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return WorkflowAwareness.ACTIVE;
    } catch (final org.camunda.bpm.engine.ProcessEngineException e) {
      // an embedded engine sharing the application's datasource practically cannot
      // be unavailable; an engine on its OWN datasource can - never fall back to
      // another adapter in that case (contract)
      log.warn(
          "Camunda7[{}]: could not determine awareness of task '{}' - reporting BPMS_UNAVAILABLE",
          adapterId,
          taskId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final Object workflowAggregateId) {

    throw new UnsupportedOperationException("awarenessOfWorkflow is implemented in a later story");

  }

  /**
   * Completes (or cancels, if an error code is given) the parked execution of an
   * asynchronous task by signaling it - the {@code Camunda7WorkflowTaskBehavior}
   * leaves the activity or propagates the BPMN error. Runs within the caller's
   * transaction for engines sharing the application's datasource.
   *
   * @param taskId The parked execution's ID
   * @param bpmnErrorCode The BPMN error code or <code>null</code> to complete
   * @param tolerateGoneTask Whether a no-longer-existing execution is tolerated
   *        (phase two is at-least-once) instead of failing
   */
  private void signalTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask) {

    if (tolerateGoneTask) {
      // check BEFORE signaling: a failing engine command would mark the joined
      // transaction rollback-only even if the exception is caught - the outbox
      // dispatcher's transaction could then never commit the DONE entry
      final var exists = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .count() > 0;
      if (!exists) {
        // at-least-once residual: the task disappeared between the dispatch-time
        // probe and this signal (e.g. a boundary event canceled it)
        log.warn(
            "Camunda7[{}]: task '{}' is gone - skipping the redelivered phase-two {}",
            adapterId,
            taskId,
            bpmnErrorCode == null
                ? "completion"
                : "cancellation");
        return;
      }
    }
    if (bpmnErrorCode == null) {
      runtimeService.signal(taskId);
    } else {
      runtimeService.signal(
          taskId,
          io.vanillabp.camunda7.wiring.Camunda7WorkflowTaskBehavior.SIGNAL_CANCEL,
          bpmnErrorCode,
          java.util.Map.of());
    }

  }

  @Override
  public void completeTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    if (usesSeparateDataSource) {
      // the engine cannot join the caller's transaction - completing here would
      // advance the process although the transaction may still roll back; the
      // completion happens in phase two (the awareness probe already verified the
      // task exists - the non-advancing check)
      return;
    }
    signalTask(taskId, null, false);

  }

  @Override
  public void completeTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    signalTask(taskId, null, true);

  }

  @Override
  public void cancelTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (usesSeparateDataSource) {
      return;
    }
    signalTask(taskId, bpmnErrorCode, false);

  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    signalTask(taskId, bpmnErrorCode, true);

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    if (usesSeparateDataSource) {
      // the engine runs on its own datasource and cannot join the caller's
      // transaction - the instance is created in phase two after the commit (see
      // class comment); starting a workflow has nothing to validate against the
      // engine (the degenerate two-phase case, like remote BPMS)
      return;
    }

    // Camunda 7 is embedded and joins the local transaction, so the workflow is started
    // completely here: the aggregate ID maps onto the Camunda business key, the workflow
    // module ID onto the Camunda tenant ID.
    final var aggregateId = aggregatePersistence.getAggregateId(workflowAggregate);

    startProcessInstance(workflowModuleId, bpmnProcessId, aggregateId);

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    if (!usesSeparateDataSource) {
      // sharing the application's datasource the workflow is started entirely in
      // phase one (needsTwoPhaseCommit... == false), so the core never schedules
      // phase two. A call here indicates a wiring problem.
      log.warn(
          "Camunda7[{}]: startWorkflowPhaseTwo was called for workflow '{}' of module '{}' (aggregate "
              + "'{}') although Camunda 7 starts workflows in phase one - ignoring (this should never "
              + "happen).",
          adapterId,
          bpmnProcessId,
          workflowModuleId,
          workflowAggregateId);
      return;
    }

    // phase two is dispatched at-least-once (outbox retries, crash recovery) - skip
    // if a running instance for this aggregate already exists (idempotency key:
    // business key + tenant + process)
    final var businessKey = String.valueOf(workflowAggregateId);
    final var alreadyStarted = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .processDefinitionKey(bpmnProcessId)
        .tenantIdIn(workflowModuleId)
        .count() > 0;
    if (alreadyStarted) {
      log.info(
          "Camunda7[{}]: workflow '{}' of module '{}' was already started for aggregate '{}' - "
              + "skipping the redelivered phase-two start",
          adapterId,
          bpmnProcessId,
          workflowModuleId,
          businessKey);
      return;
    }

    startProcessInstance(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

}
