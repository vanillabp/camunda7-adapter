package io.vanillabp.camunda7.processservice;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
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
public class Camunda7ProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  /**
   * The embedded engine's runtime service used to start process instances. Provided by
   * the platform module (Spring Boot) which wires the embedded engine sharing the
   * application's data source and transaction manager.
   */
  private final RuntimeService runtimeService;

  /**
   * The embedded engine's task service - user-task operations (story 24).
   */
  private final org.camunda.bpm.engine.TaskService taskService;

  /**
   * Whether this adapter id's engine runs on its OWN datasource (see class comment):
   * engine commands then do not join the caller's transaction and starting workflows
   * uses the two-phase pattern.
   */
  private final boolean usesSeparateDataSource;

  /**
   * The viewer/history API (story 26) - definitions, BPMN XML and the instance
   * timeline, served by the engine's repository and history services.
   */
  private final Camunda7WorkflowViewer viewer;

  /**
   * The embedded engine's history service - workflow awareness of ENDED workflows
   * and the viewer/history API.
   */
  private final org.camunda.bpm.engine.HistoryService historyService;

  /**
   * The engine's repository service - the deployed BPMN model tells an activity
   * which creates a scope of its own (boundary events, multi-instance) from one
   * which does not, which decides where a task-scoped push has to write.
   */
  private final org.camunda.bpm.engine.RepositoryService repositoryService;

  /**
   * The core's sync model (story 28). Camunda 7 is EMBEDDED: BPMN expressions read
   * the aggregate LIVE (see the EL resolver of story 21b), so nothing has to be
   * pushed - the adapter's default is therefore
   * {@link io.vanillabp.integration.adapter.spi.AggregateSyncMode#NONE}. What the
   * application DOES share ({@code @SyncWithBPMS}) is written as process variables
   * for one purpose only: context information for operators in Camunda's Cockpit.
   * VanillaBP never reads those variables back (the aggregate is the truth); the
   * only variables read are the ones a {@code @TaskParam} asks for, which the BPMN
   * model provides deliberately.
   */
  private final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync;

  /**
   * The default of this adapter: nothing is shared unless the application asks for
   * it ({@code @SyncWithBPMS}).
   */
  /**
   * The technical variable written when the application shares nothing: Camunda 7
   * evaluates conditional events on variable changes, so SOMETHING has to change for
   * the engine to look. Its value is the time of the push - only there to make every
   * write a change.
   */
  public static final String AGGREGATE_CHANGED_MARKER = "vanillabpAggregateChanged";

  public static final io.vanillabp.integration.adapter.spi.AggregateSyncMode SYNC_MODE = io.vanillabp.integration.adapter.spi.AggregateSyncMode.NONE;

  /**
   * The core's name-clash-avoidance model (story 35): translates process ids, message
   * names and error codes into what the ENGINE knows, and decides whether operations
   * run in a Camunda tenant. May be <code>null</code> (tests): the workflow module id
   * is the tenant then, as before.
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * The tenant name configured for this adapter id or <code>null</code> (then the
   * workflow module id names the tenant).
   */
  private String configuredTenantId;

  /**
   * Sets the name-clash-avoidance support and the configured tenant name (the
   * platform modules construct this service and inject them afterwards).
   *
   * @param scoping The name-clash-avoidance support
   * @param configuredTenantId The configured tenant name or <code>null</code>
   */
  public void setScoping(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final String configuredTenantId) {

    this.scoping = scoping;
    this.configuredTenantId = configuredTenantId;

  }

  /**
   * Correlates a message which STARTS a workflow, honoring the module's tenant
   * (story 35: there may be none).
   */
  private void startByMessage(
      final String workflowModuleId,
      final String messageName,
      final String businessKey) {

    var correlation = runtimeService
        .createMessageCorrelation(scopedIdentifier(workflowModuleId, messageName))
        .processInstanceBusinessKey(businessKey);
    final var tenantId = tenantIdOf(workflowModuleId);
    correlation = tenantId != null
        ? correlation.tenantId(tenantId)
        : correlation.withoutTenantId();
    correlation.correlateStartMessage();

  }

  /**
   * Whether a RUNNING instance of the given workflow exists - the idempotency check
   * of the two-phase start. Honors the module's tenant and the scoped process id
   * (story 35).
   */
  private boolean instanceExists(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String businessKey) {

    var query = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .processDefinitionKey(scopedProcessId(workflowModuleId, bpmnProcessId));
    final var tenantId = tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    return query.count() > 0;

  }

  /**
   * The BPMN process id as the engine knows it.
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * A message name / error code as the engine knows it.
   */
  private String scopedIdentifier(
      final String workflowModuleId,
      final String identifier) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * The Camunda tenant an operation runs in, or <code>null</code> when the module's
   * mode uses no tenant (story 35).
   */
  private String tenantIdOf(
      final String workflowModuleId) {

    return io.vanillabp.camunda7.wiring.Camunda7Scoping
        .tenantIdFor(scoping, workflowModuleId, adapterId, configuredTenantId);

  }

  /**
   * Convenience constructor without the sync model (tests) - no operator context
   * is written then.
   */
  public Camunda7ProcessService(
      final String adapterId,
      final RuntimeService runtimeService,
      final org.camunda.bpm.engine.TaskService taskService,
      final org.camunda.bpm.engine.RepositoryService repositoryService,
      final org.camunda.bpm.engine.HistoryService historyService,
      final boolean usesSeparateDataSource) {

    this(adapterId, runtimeService, taskService, repositoryService, historyService, usesSeparateDataSource, null);

  }

  public Camunda7ProcessService(
      final String adapterId,
      final RuntimeService runtimeService,
      final org.camunda.bpm.engine.TaskService taskService,
      final org.camunda.bpm.engine.RepositoryService repositoryService,
      final org.camunda.bpm.engine.HistoryService historyService,
      final boolean usesSeparateDataSource,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync) {

    this.aggregateSync = aggregateSync;
    this.adapterId = adapterId;
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.usesSeparateDataSource = usesSeparateDataSource;
    this.historyService = historyService;
    this.repositoryService = repositoryService;
    this.viewer = new Camunda7WorkflowViewer(adapterId, repositoryService, historyService, runtimeService);

  }

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

    return startProcessInstance(workflowModuleId, bpmnProcessId, workflowAggregateId, null);

  }

  /**
   * Starts a process instance, writing the aggregate's shared attributes as
   * process variables - CONTEXT INFORMATION FOR OPERATORS only (see
   * {@link #aggregateSync}); nothing reads them back.
   *
   * @param workflowModuleId The workflow module ID (the Camunda tenant ID)
   * @param bpmnProcessId The BPMN process ID to start
   * @param workflowAggregateId The workflow-aggregate ID (the business key)
   * @param aggregate The workflow aggregate or <code>null</code> if unavailable
   * @return The started process instance
   */
  public ProcessInstance startProcessInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final A aggregate) {

    // the aggregate id was validated (non-null, non-blank) once in the core's
    // MigrationProcessService before phase one is invoked
    final var businessKey = String.valueOf(workflowAggregateId);

    final var tenantId = tenantIdOf(workflowModuleId);
    var builder = runtimeService
        .createProcessInstanceByKey(scopedProcessId(workflowModuleId, bpmnProcessId));
    builder = tenantId != null
        ? builder.processDefinitionTenantId(tenantId)
        : builder.processDefinitionWithoutTenantId();
    final var processInstance = builder
        .businessKey(businessKey)
        .setVariables(operatorContext(aggregate))
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

  /**
   * The aggregate's shared attributes - written as process variables for operators
   * (Cockpit context). Empty unless the application opted in, since this adapter's
   * default is {@code NONE}.
   *
   * @param aggregate The workflow aggregate or <code>null</code>
   * @return The variables (never <code>null</code>)
   */
  private java.util.Map<String, Object> operatorContext(
      final A aggregate) {

    if ((aggregateSync == null) || (aggregate == null)) {
      return java.util.Map.of();
    }
    // a plain copy, NOT Map.copyOf: a shared attribute may well be null and the
    // engine stores a null variable just fine (Map.copyOf would throw)
    return new java.util.LinkedHashMap<>(aggregateSync.syncedValues(aggregate, SYNC_MODE));

  }

  /**
   * Refreshes the operator context of a running workflow (see
   * {@link #operatorContext}) - a no-op unless the application shares attributes.
   *
   * @param executionId The execution to write the variables at
   * @param aggregate The workflow aggregate or <code>null</code>
   */
  private void refreshOperatorContext(
      final String executionId,
      final A aggregate) {

    final var variables = operatorContext(aggregate);
    if (variables.isEmpty()) {
      return;
    }
    runtimeService.setVariables(executionId, variables);

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

    // the business key IS the aggregate ID. A running instance means ACTIVE; an
    // ENDED one is reported as COMPLETED as long as the engine's history still
    // holds it (history level != NONE and within 'history-time-to-live') - the
    // honest answer the SPI asks for: it keeps a re-dispatched start from
    // starting a second instance of a workflow which already ran to its end, and
    // it makes the viewer/history API work for ended workflows. Once the history
    // was cleaned up an ended instance is indistinguishable from a never-existing
    // one - both map to UNKNOWN_TO_BPMS (the caller's guiding error explains the
    // causes).
    try {
      final var active = runtimeService
          .createProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(workflowAggregateId))
          .count() > 0;
      if (active) {
        return WorkflowAwareness.ACTIVE;
      }
      final var ended = historyService
          .createHistoricProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(workflowAggregateId))
          .finished()
          .count() > 0;
      return ended
          ? WorkflowAwareness.COMPLETED
          : WorkflowAwareness.UNKNOWN_TO_BPMS;
    } catch (final org.camunda.bpm.engine.ProcessEngineException e) {
      log.warn(
          "Camunda7[{}]: could not determine awareness of the workflow of aggregate '{}' - "
              + "reporting BPMS_UNAVAILABLE",
          adapterId,
          workflowAggregateId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

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
  public WorkflowAwareness awarenessOfUserTask(
      final Object workflowAggregateId,
      final String taskId) {

    // user-task IDs (ACT_RU_TASK) are globally unique within the engine - the
    // business key is verified like in awarenessOfTask
    try {
      final var task = taskService
          .createTaskQuery()
          .taskId(taskId)
          .singleResult();
      if (task == null) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      final var processInstance = runtimeService
          .createProcessInstanceQuery()
          .processInstanceId(task.getProcessInstanceId())
          .singleResult();
      if ((processInstance == null) || !String.valueOf(workflowAggregateId).equals(processInstance.getBusinessKey())) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return WorkflowAwareness.ACTIVE;
    } catch (final org.camunda.bpm.engine.ProcessEngineException e) {
      log.warn(
          "Camunda7[{}]: could not determine awareness of user task '{}' - reporting BPMS_UNAVAILABLE",
          adapterId,
          taskId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  /**
   * Completes (or cancels by BPMN error) a user task. Runs within the caller's
   * transaction for engines sharing the application's datasource.
   *
   * @param taskId The user task's ID (ACT_RU_TASK)
   * @param bpmnErrorCode The BPMN error code or <code>null</code> to complete
   * @param tolerateGoneTask Whether a no-longer-existing task is tolerated (phase
   *        two is at-least-once)
   */
  private void executeUserTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask) {

    if (tolerateGoneTask) {
      // check BEFORE executing - see signalTask: a failing engine command would
      // mark the joined transaction rollback-only even if the exception is caught
      final var exists = taskService
          .createTaskQuery()
          .taskId(taskId)
          .count() > 0;
      if (!exists) {
        log.warn(
            "Camunda7[{}]: user task '{}' is gone - skipping the redelivered phase-two {}",
            adapterId,
            taskId,
            bpmnErrorCode == null
                ? "completion"
                : "cancellation");
        return;
      }
    }
    if (bpmnErrorCode == null) {
      taskService.complete(taskId);
    } else {
      // routes the workflow through an error boundary event on the user task
      taskService.handleBpmnError(taskId, bpmnErrorCode);
    }

  }

  @Override
  public void completeUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    if (usesSeparateDataSource) {
      return;
    }
    executeUserTask(taskId, null, false);

  }

  @Override
  public void completeUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    executeUserTask(taskId, null, true);

  }

  @Override
  public void cancelUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (usesSeparateDataSource) {
      return;
    }
    executeUserTask(taskId, scopedIdentifier(workflowModuleId, bpmnErrorCode), false);

  }

  @Override
  public void cancelUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    executeUserTask(taskId, scopedIdentifier(workflowModuleId, bpmnErrorCode), true);

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
    refreshOperatorContext(taskId, workflowAggregate);
    signalTask(taskId, scopedIdentifier(workflowModuleId, bpmnErrorCode), false);

  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    signalTask(taskId, scopedIdentifier(workflowModuleId, bpmnErrorCode), true);

  }

  /**
   * The V1-compatible name of the LOCAL variable holding the expected correlation
   * id at a message subscription's execution:
   * <code>&lt;bpmnProcessId&gt;-&lt;messageName&gt;</code>. Applications set this
   * local variable at the receiving scope; a correlation carrying a correlation id
   * only matches executions whose variable equals it.
   */
  public static String correlationIdVariableName(
      final String bpmnProcessId,
      final String messageName) {

    return bpmnProcessId
        + "-"
        + messageName;

  }

  private org.camunda.bpm.engine.runtime.MessageCorrelationBuilder messageCorrelation(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String businessKey,
      final String correlationId) {

    var correlation = runtimeService
        .createMessageCorrelation(scopedIdentifier(workflowModuleId, messageName))
        .processInstanceBusinessKey(businessKey);
    final var correlationTenantId = tenantIdOf(workflowModuleId);
    correlation = correlationTenantId != null
        ? correlation.tenantId(correlationTenantId)
        : correlation.withoutTenantId();
    if (correlationId != null) {
      // V1 convention: the local variable '<bpmnProcessId>-<messageName>' at the
      // subscription's execution holds the expected correlation id
      correlation = correlation.localVariableEquals(
          correlationIdVariableName(bpmnProcessId, messageName),
          correlationId);
    }
    return correlation;

  }

  /**
   * Broadcasts the signal inside the caller's transaction: the embedded engine
   * shares it, so a rollback takes the broadcast with it. An engine on its OWN
   * datasource cannot join that transaction - it broadcasts in phase two, like a
   * remote BPMS (see {@link #needsTwoPhaseCommitForStartingWorkflows()}).
   */
  @Override
  public void sendSignalPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

    if (usesSeparateDataSource) {
      return;
    }
    broadcastSignal(workflowModuleId, signalName);

  }

  @Override
  public void sendSignalPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

    broadcastSignal(workflowModuleId, signalName);

  }

  /**
   * A signal reaches every subscription of the workflow module's scope - the
   * tenant it is deployed into, respectively no tenant where the module prefixes
   * its identifiers (story 35). No variables travel: a signal transports its name,
   * the workflow aggregate stays the source of truth.
   */
  private void broadcastSignal(
      final String workflowModuleId,
      final String signalName) {

    var signal = runtimeService
        .createSignalEvent(scopedIdentifier(workflowModuleId, signalName));
    final var signalTenantId = tenantIdOf(workflowModuleId);
    signal = signalTenantId != null
        ? signal.tenantId(signalTenantId)
        : signal.withoutTenantId();
    signal.send();

  }

  @Override
  public void correlateMessagePhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

    if (usesSeparateDataSource) {
      // the engine cannot join the caller's transaction - correlating here would
      // advance the process although the transaction may still roll back
      return;
    }
    // PAYLOAD DOCTRINE: no variables are set - the aggregate is the source of truth
    final var businessKey = String.valueOf(aggregatePersistence.getAggregateId(workflowAggregate));
    messageCorrelation(workflowModuleId, bpmnProcessId, messageName, businessKey, correlationId)
        .correlateWithResult();

  }

  @Override
  public void correlateMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    final var businessKey = String.valueOf(workflowAggregateId);
    // check BEFORE correlating (rollback-only pitfall, see signalTask): a waiting
    // subscription gone by dispatch time is the at-least-once residual
    final var subscriptionWaiting = runtimeService
        .createExecutionQuery()
        .messageEventSubscriptionName(messageName)
        .processInstanceBusinessKey(businessKey)
        .count() > 0;
    if (!subscriptionWaiting) {
      log.warn(
          "Camunda7[{}]: no waiting subscription for message '{}' of workflow aggregate '{}' - "
              + "skipping the redelivered phase-two correlation",
          adapterId,
          messageName,
          businessKey);
      return;
    }
    messageCorrelation(workflowModuleId, bpmnProcessId, messageName, businessKey, correlationId)
        .correlateWithResult();

  }

  @Override
  public void aggregateChangedPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    if (usesSeparateDataSource) {
      // the engine cannot join the caller's transaction - writing here would show
      // values of a transaction which may still roll back
      return;
    }
    pushAggregate(
        String.valueOf(aggregatePersistence.getAggregateId(workflowAggregate)), workflowAggregate, taskId, false);

  }

  @Override
  public void aggregateChangedPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    final var aggregate = aggregatePersistence.loadById(workflowAggregateId);
    pushAggregate(String.valueOf(workflowAggregateId), aggregate, taskId, true);

  }

  /**
   * Writes the aggregate's shared values into the workflow (see
   * {@link #operatorContext}) and lets the engine re-evaluate what waits for a
   * change.
   * <p>
   * Camunda 7 evaluates conditional events when a variable of their scope changes,
   * so the write itself is the trigger - and it has to happen even when the
   * application shares nothing ({@code @SyncWithBPMS} is opt-in here), which is why
   * a technical marker variable is written alongside. Camunda 7 reads the aggregate
   * LIVE through the EL resolver, so a condition sees the current state either way;
   * without the write nothing would look.
   *
   * @param businessKey The aggregate's ID
   * @param aggregate The workflow aggregate or <code>null</code>
   * @param taskId The parked execution whose scope receives the values, or
   *        <code>null</code> for the workflow's global scope
   * @param tolerateGoneWorkflow Whether a workflow gone by now is tolerated (phase
   *        two is at-least-once) instead of failing
   */
  private void pushAggregate(
      final String businessKey,
      final A aggregate,
      final String taskId,
      final boolean tolerateGoneWorkflow) {

    final var variables = new java.util.LinkedHashMap<String, Object>(operatorContext(aggregate));
    if (variables.isEmpty()) {
      // nothing is shared (this adapter's default) - without a variable event the
      // engine would not look at its conditional events at all, so a technical
      // marker is written instead
      variables.put(AGGREGATE_CHANGED_MARKER, System.currentTimeMillis());
    }

    if (taskId != null) {
      // check BEFORE writing (rollback-only pitfall, see signalTask)
      final var execution = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .singleResult();
      if (execution == null) {
        if (!tolerateGoneWorkflow) {
          throw new io.vanillabp.spi.process.TaskNotFoundException(
              ("The task '%s' of the workflow of aggregate '%s' is not active in Camunda 7 (adapter "
                  + "'%s')! Either the task was completed meanwhile or the id is not the one reported "
                  + "to the @TaskId parameter.")
                  .formatted(taskId, businessKey, adapterId));
        }
        log.warn(
            "Camunda7[{}]: task '{}' of workflow aggregate '{}' is gone - skipping the redelivered "
                + "phase-two push of the aggregate",
            adapterId,
            taskId,
            businessKey);
        return;
      }
      // the scope the task RUNS IN - the process, an embedded subprocess, or the one
      // iteration of a multi-instance embedded subprocess it belongs to. Writing on
      // the task's own execution would reach a boundary conditional event and nothing
      // else, while the scope is what an event subprocess with a conditional start
      // event listens on
      runtimeService
          .setVariablesLocal(
              flowScopeExecutionIdOf(execution.getProcessInstanceId(), taskId),
              variables);
      return;
    }

    final var processInstance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .singleResult();
    if (processInstance == null) {
      if (!tolerateGoneWorkflow) {
        throw new io.vanillabp.spi.process.WorkflowNotFoundException(
            ("The workflow of aggregate '%s' is not active in Camunda 7 (adapter '%s') - its changed "
                + "aggregate cannot be pushed!")
                .formatted(businessKey, adapterId));
      }
      log.warn(
          "Camunda7[{}]: the workflow of aggregate '{}' is gone - skipping the redelivered phase-two "
              + "push of the aggregate",
          adapterId,
          businessKey);
      return;
    }
    runtimeService.setVariables(processInstance.getId(), variables);

  }

  /**
   * The execution of the scope the task with the given ID RUNS IN: the process
   * instance, an embedded subprocess, or the one iteration of a multi-instance
   * embedded subprocess it belongs to.
   * <p>
   * Not the task's own context: an activity with boundary events (and every instance
   * of a multi-instance activity) gets a scope of its own in the engine, and writing
   * there would serve a boundary conditional event and nothing else. The scope AROUND
   * the task is what an event subprocess with a conditional start event listens on,
   * and what the rest of that scope reads.
   * <p>
   * The walk goes up the execution tree: from the task's execution to the closest
   * SCOPE execution, skipping the execution of the task's own activity scope and the
   * technical wrapper around the instances of a multi-instance activity.
   *
   * @param processInstanceId The workflow's process instance
   * @param taskId The parked execution of the task
   * @return The execution to write the local variables at (the process instance if
   *         the task cannot be located)
   */
  private String flowScopeExecutionIdOf(
      final String processInstanceId,
      final String taskId) {

    final var executions = runtimeService
        .createExecutionQuery()
        .processInstanceId(processInstanceId)
        .list()
        .stream()
        .filter(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::isInstance)
        .map(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::cast)
        .collect(
            java.util.stream.Collectors
                .toMap(
                    org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity::getId,
                    execution -> execution));

    var current = executions.get(taskId);
    if (current == null) {
      return processInstanceId;
    }
    final var processDefinitionId = current.getProcessDefinitionId();
    if (current.isScope() && activityHasAScopeOfItsOwn(processDefinitionId, current.getActivityId())) {
      // the scope of the task itself - the task runs in the scope around it
      current = executions.get(current.getParentId());
    }
    while (current != null) {
      if (current.isScope() && !isMultiInstanceBody(current)) {
        return current.getId();
      }
      current = executions.get(current.getParentId());
    }
    return processInstanceId;

  }

  /**
   * Whether the BPMN activity creates a scope of its own in the engine: an activity
   * with boundary events attached, or a multi-instance activity (whose instances each
   * get one).
   *
   * @param processDefinitionId The definition the workflow runs on
   * @param activityId The activity to look at
   * @return Whether the engine gives that activity a scope
   */
  private boolean activityHasAScopeOfItsOwn(
      final String processDefinitionId,
      final String activityId) {

    if (activityId == null) {
      return false;
    }
    try {
      final var model = repositoryService.getBpmnModelInstance(processDefinitionId);
      final var element = model.getModelElementById(activityId);
      if (element instanceof org.camunda.bpm.model.bpmn.instance.Activity activity) {
        if (activity.getLoopCharacteristics() != null) {
          return true;
        }
        return model
            .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.BoundaryEvent.class)
            .stream()
            .anyMatch(boundaryEvent -> activityId.equals(boundaryEvent.getAttachedTo().getId()));
      }
      return false;
    } catch (final RuntimeException e) {
      log
          .debug(
              "Camunda7[{}]: could not read the model of process definition '{}' - assuming activity '{}' "
                  + "has no scope of its own",
              adapterId,
              processDefinitionId,
              activityId,
              e);
      return false;
    }

  }

  /**
   * Whether the execution is the technical wrapper around the instances of a
   * multi-instance activity. Its variables would be shared by all of them, so it is
   * never the scope a push is meant for.
   *
   * @param execution The execution to look at
   * @return Whether it is a multi-instance body
   */
  private boolean isMultiInstanceBody(
      final org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity execution) {

    final var activityId = execution.getActivityId();
    // the engine names it "<activity id>#multiInstanceBody"
    return (activityId != null) && activityId.endsWith("#"
        + org.camunda.bpm.engine.ActivityTypes.MULTI_INSTANCE_BODY);

  }

  @Override
  public void startWorkflowByMessagePhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String messageName) {

    if (usesSeparateDataSource) {
      return;
    }
    final var businessKey = String.valueOf(aggregatePersistence.getAggregateId(workflowAggregate));
    startByMessage(workflowModuleId, messageName, businessKey);

  }

  @Override
  public void startWorkflowByMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName) {

    // at-least-once: skip if an instance for this aggregate already exists (the
    // same idempotency contract as startWorkflowPhaseTwo)
    final var businessKey = String.valueOf(workflowAggregateId);
    final var alreadyStarted = instanceExists(workflowModuleId, bpmnProcessId, businessKey);
    if (alreadyStarted) {
      log.info(
          "Camunda7[{}]: workflow '{}' of module '{}' was already started for aggregate '{}' - "
              + "skipping the redelivered phase-two start-by-message",
          adapterId,
          bpmnProcessId,
          workflowModuleId,
          businessKey);
      return;
    }
    startByMessage(workflowModuleId, messageName, businessKey);

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

    startProcessInstance(workflowModuleId, bpmnProcessId, aggregateId, workflowAggregate);

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
    final var alreadyStarted = instanceExists(workflowModuleId, bpmnProcessId, businessKey);
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


  @Override
  public java.util.List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewer.getProcessDefinitions(
        workflowModuleId,
        scopedProcessId(workflowModuleId, bpmnProcessId),
        tenantIdOf(workflowModuleId),
        workflowAggregateId,
        historyContext);

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    return viewer.getBpmnXml(processDefinitionId);

  }

  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewer.getWorkflowHistory(
        workflowModuleId,
        scopedProcessId(workflowModuleId, bpmnProcessId),
        tenantIdOf(workflowModuleId),
        workflowAggregateId,
        historyContext);

  }

}
