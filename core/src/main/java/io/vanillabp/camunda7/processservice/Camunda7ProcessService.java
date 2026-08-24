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
 * Camunda 7 runs embedded in the application's JVM and normally shares its database and
 * its transaction. VanillaBP nevertheless progresses the process AFTER the commit, the
 * way every other BPMS does: {@link #needsTwoPhaseCommitForStartingWorkflows()} returns
 * {@code true} unconditionally and every progressing operation is scheduled through the
 * phase-two outbox.
 * <p>
 * <b>Why, although sharing the transaction was comfortable:</b> an engine command which
 * loses a concurrency conflict cannot be repeated inside the caller's transaction. Every
 * command joins that transaction, so a failing one leaves it rollback-only - and
 * repeating just the engine part in a transaction of its own would advance the process
 * while the application rolls back, which is the ghost progress the two-phase pattern
 * exists to prevent. An application answering an open task while a timer job of the same
 * workflow runs met that as an {@code OptimisticLockingException} it could do nothing
 * about. Through the outbox the operation is simply repeated.
 * <p>
 * <b>What that costs:</b> the operation happens after the commit, so "the engine has done
 * it when my method returns" no longer holds. What phase one still does is ASK: an
 * embedded engine can answer for free and in the same transaction, so a task which does
 * not exist or a workflow the engine does not know is still reported synchronously, by
 * the core's election probe, before anything is scheduled.
 * <p>
 * Phase two is dispatched at-least-once (outbox retries, crash recovery), so every
 * operation is idempotent: starting skips an existing instance with the same business key
 * and tenant, completing or cancelling a task checks that the task is still there, and
 * correlating checks the subscription.
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
   * The embedded engine's task service - user-task operations.
   */
  private final org.camunda.bpm.engine.TaskService taskService;

  /**
   * The viewer/history API - definitions, BPMN XML and the instance
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
   * The core's sync model. Camunda 7 shares like every other
   * BPMS: the values are written as process variables at every point this adapter talks
   * to the engine, and the engine's expressions read those variables. Being embedded is
   * no reason to do it differently - the engine evaluates its models against its own
   * variables either way, and a model reading something else would work here and break
   * on every remote BPMS.
   * <p>
   * VanillaBP never reads these variables back: the aggregate is the truth. The only
   * variables read are the ones a {@code @TaskParam} asks for, which the BPMN model
   * provides deliberately.
   */
  private final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync;

  /**
   * The technical variable written when the application shares nothing: Camunda 7
   * evaluates conditional events on variable changes, so SOMETHING has to change for
   * the engine to look. Its value is the time of the push - only there to make every
   * write a change.
   */
  public static final String AGGREGATE_CHANGED_MARKER = "vanillabpAggregateChanged";

  /**
   * The default of this adapter: everything is shared unless the
   * application excludes it ({@code @NoSyncWithBPMS}), which is the default of every
   * VanillaBP adapter - a model must not depend on which BPMS runs it.
   */
  public static final io.vanillabp.integration.adapter.spi.AggregateSyncMode SYNC_MODE = io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL;

  /**
   * The core's name-clash-avoidance model: translates process ids, message
   * names and error codes into what the ENGINE knows, and decides whether operations
   * run in a Camunda tenant. May be <code>null</code> (tests): the workflow module id
   * is the tenant then, as before.
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * Which serialization format nested shared values are stored in, resolved per workflow
   * with a fallback to the workflow module and the adapter. Provided by the
   * platform integration, which binds the configuration; <code>null</code> in tests -
   * the engine's default applies then.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats;

  /**
   * @param serializationFormats The format resolution of the platform integration
   */
  public void setSerializationFormats(
      final io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats) {

    this.serializationFormats = serializationFormats;

  }

  /**
   * The configured format for nested shared values of one workflow, or
   * <code>null</code>.
   */
  private String serializationFormatOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return serializationFormats != null
        ? serializationFormats.formatFor(workflowModuleId, bpmnProcessId)
        : null;

  }

  /**
   * The tenant name configured for this adapter id or <code>null</code> (then the
   * workflow module id names the tenant).
   */
  private String configuredTenantId;

  /**
   * Narrows a runtime query down to the scope an awareness probe was asked about.
   * <p>
   * <b>Why the probes need it.</b> A Camunda 7 business key is the workflow-aggregate id,
   * unique per aggregate type and not across an engine, so the key alone answers for any
   * workflow of any workflow module which happens to count from the same number - and for
   * processes this application never wired at all, an engine being a database somebody
   * else may share. What tells them apart is what the scope names: the workflow module and
   * its BPMN processes, translated into the process definition keys the engine knows and
   * the tenant the module runs in.
   *
   * @param query The query to narrow
   * @param scope What the probe was asked about
   * @return The same query, narrowed
   */
  private org.camunda.bpm.engine.runtime.ProcessInstanceQuery within(
      final org.camunda.bpm.engine.runtime.ProcessInstanceQuery query,
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope) {

    final var scoped = query.processDefinitionKeyIn(scopedProcessIdsOf(scope));
    final var tenantId = tenantIdOf(scope.workflowModuleId());
    return tenantId != null
        ? scoped.tenantIdIn(tenantId)
        : scoped.withoutTenantId();

  }

  /**
   * Narrows a history query down to the same scope.
   */
  private org.camunda.bpm.engine.history.HistoricProcessInstanceQuery withinHistory(
      final org.camunda.bpm.engine.history.HistoricProcessInstanceQuery query,
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope) {

    final var scoped = query.processDefinitionKeyIn(scopedProcessIdsOf(scope));
    final var tenantId = tenantIdOf(scope.workflowModuleId());
    return tenantId != null
        ? scoped.tenantIdIn(tenantId)
        : scoped.withoutTenantId();

  }

  /**
   * @param scope What the probe was asked about
   * @return The process definition keys the engine knows that scope's processes by
   */
  private String[] scopedProcessIdsOf(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope) {

    return scope
        .bpmnProcessIds()
        .stream()
        .map(bpmnProcessId -> scopedProcessId(scope.workflowModuleId(), bpmnProcessId))
        .toArray(String[]::new);

  }

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
   * (a module prefixing its identifiers has none, see decision 3 in the
   * repository's README.md).
   */
  private void startByMessage(
      final String workflowModuleId,
      final String messageName,
      final String businessKey,
      final java.util.Map<String, Object> sharedValues) {

    var correlation = runtimeService
        .createMessageCorrelation(scopedIdentifier(workflowModuleId, messageName))
        .processInstanceBusinessKey(businessKey);
    final var tenantId = tenantIdOf(workflowModuleId);
    correlation = tenantId != null
        ? correlation.tenantId(tenantId)
        : correlation.withoutTenantId();
    // the new instance starts with the values its model may read, exactly
    // like a workflow started without a message
    correlation
        .setVariables(sharedValues)
        .correlateStartMessage();

  }

  /**
   * Whether a RUNNING instance of the given workflow exists - the idempotency check
   * of the two-phase start. Honors the module's tenant and the scoped process id.
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
   * mode uses no tenant.
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
      final org.camunda.bpm.engine.HistoryService historyService) {

    this(adapterId, runtimeService, taskService, repositoryService, historyService, null);

  }

  public Camunda7ProcessService(
      final String adapterId,
      final RuntimeService runtimeService,
      final org.camunda.bpm.engine.TaskService taskService,
      final org.camunda.bpm.engine.RepositoryService repositoryService,
      final org.camunda.bpm.engine.HistoryService historyService,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync) {

    this.aggregateSync = aggregateSync;
    this.adapterId = adapterId;
    this.runtimeService = runtimeService;
    this.taskService = taskService;
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

    // ALWAYS - see the class comment. Sharing the caller's transaction would make a
    // progressing operation which loses a concurrency conflict unrepeatable, and an
    // adapter id with its OWN datasource cannot join that transaction anyway
    return true;

  }

  @Override
  public boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    // The conflict this adapter went two-phase for: the engine reports a
    // row another transaction touched as OptimisticLockingException, and the next
    // attempt simply wins - which is exactly what Camunda's own job executor does
    // with a failed job. Everything else is repeated as well, because an engine
    // command may fail for a reason which passes (a locked instance, a database
    // hiccup) - except a request the engine rejects as wrong: a task id which does
    // not exist or an argument it cannot accept looks the same on every attempt.
    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof org.camunda.bpm.engine.BadUserRequestException) {
        return false;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return true;

  }

  @Override
  public boolean deliversTasksAtLeastOnce() {

    // Camunda 7 hands a task to the handler INSIDE its own (job) transaction
    // (TaskInvocationContext#runInCurrentTransaction), so aggregate changes and engine
    // state commit or roll back together: a redelivered job proves that NOTHING was
    // committed, and there is nothing a delivery record could remember.
    // Deduplicating would cost a store access per task and buy nothing.
    //
    // With an OWN engine datasource the two commits are no longer one, so the window
    // the remote adapters have exists here as well. It is deliberately not covered:
    // the identity of a delivery would have to be invented from the execution's
    // activity instance, and the rule which held before this feature - key business
    // decisions on the state of the workflow aggregate - carries that setup.
    return false;

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
        .setVariables(sharedValues(aggregate, workflowModuleId, bpmnProcessId))
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
   * The values the aggregate shares with the BPMS, as Camunda 7 process variables:
   * what the engine's expressions read, and what an operator sees in
   * Cockpit. Nested structures travel as JSON strings, see
   * {@link io.vanillabp.camunda7.sync.Camunda7Variables}.
   *
   * @param aggregate The workflow aggregate or <code>null</code>
   * @return The variables (never <code>null</code>)
   */
  private java.util.Map<String, Object> sharedValues(
      final A aggregate,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if ((aggregateSync == null) || (aggregate == null)) {
      return java.util.Map.of();
    }
    return io.vanillabp.camunda7.sync.Camunda7Variables
        .of(
            aggregateSync.syncedValues(aggregate, SYNC_MODE),
            serializationFormatOf(workflowModuleId, bpmnProcessId));

  }

  /**
   * The shared values of an aggregate this method has to read first - the shape phase
   * two needs, where the caller's transaction is committed and only the ID is at hand.
   * A persistence which cannot read the aggregate must not keep the operation from
   * happening, so a failure yields no variables rather than an exception.
   *
   * @param aggregatePersistence The aggregate's persistence (may be
   *          <code>null</code>)
   * @param workflowAggregateId The aggregate's ID
   * @return The variables (never <code>null</code>)
   */
  private java.util.Map<String, Object> sharedValues(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return sharedValues(
        aggregateForOperatorContext(aggregatePersistence, workflowAggregateId),
        workflowModuleId,
        bpmnProcessId);

  }

  /**
   * Writes the shared values into a running workflow (see {@link #sharedValues}) - a
   * no-op where the aggregate shares nothing at all.
   *
   * @param executionId The execution to write the variables at
   * @param aggregate The workflow aggregate or <code>null</code>
   */
  private void refreshSharedValues(
      final String executionId,
      final A aggregate,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var variables = sharedValues(aggregate, workflowModuleId, bpmnProcessId);
    if (variables.isEmpty()) {
      return;
    }
    runtimeService.setVariables(executionId, variables);

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // the task ID of a Camunda 7 @TaskId handler is the parked execution's ID, unique
    // within the engine. That the business key matches rules out an unrelated
    // aggregate; what it does NOT rule out is a workflow of another workflow module of
    // this engine carrying the same aggregate id, so the instance has to be one of this
    // adapter's own scopes as well
    try {
      final var execution = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .singleResult();
      if (execution == null) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return awarenessOfInstance(scope, execution.getProcessInstanceId(), workflowAggregateId);
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
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
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
    // Both queries run within the scope the probe was asked about.
    // A business key is the aggregate id, unique per aggregate type and not across an
    // application, so the unscoped question answers ACTIVE for the workflow of another
    // workflow module of this engine which happens to count from the same number.
    try {
      final var businessKey = String.valueOf(workflowAggregateId);
      final var active = within(
          runtimeService
              .createProcessInstanceQuery()
              .processInstanceBusinessKey(businessKey),
          scope)
          .count() > 0;
      if (active) {
        return WorkflowAwareness.ACTIVE;
      }
      final var ended = withinHistory(
          historyService
              .createHistoricProcessInstanceQuery()
              .processInstanceBusinessKey(businessKey)
              .finished(),
          scope)
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
   * Whether the process instance behind a task belongs to the scope the probe was asked
   * about AND carries the expected business key. Both are needed:
   * the business key rules out an unrelated aggregate, the scope rules out the same
   * aggregate id in another workflow module or another application of this engine.
   *
   * @param scope What the probe was asked about
   * @param processInstanceId The instance the task belongs to
   * @param workflowAggregateId The aggregate the caller means
   * @return {@link WorkflowAwareness#ACTIVE} if this adapter may claim the task
   */
  private WorkflowAwareness awarenessOfInstance(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final String processInstanceId,
      final Object workflowAggregateId) {

    final var own = within(
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .processInstanceBusinessKey(String.valueOf(workflowAggregateId)),
        scope)
        .count() > 0;
    return own
        ? WorkflowAwareness.ACTIVE
        : WorkflowAwareness.UNKNOWN_TO_BPMS;

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

    signalTask(taskId, bpmnErrorCode, tolerateGoneTask, null);

  }

  /**
   * @param beforeSignal Runs after the task was found and before it is signalled -
   *        used to refresh what an operator sees along with a cancellation
   */
  private void signalTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask,
      final Runnable beforeSignal) {

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
    if (beforeSignal != null) {
      beforeSignal.run();
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
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // user-task IDs (ACT_RU_TASK) are unique within the engine - the business key AND
    // the scope are verified like in awarenessOfTask
    try {
      final var task = taskService
          .createTaskQuery()
          .taskId(taskId)
          .singleResult();
      if (task == null) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return awarenessOfInstance(scope, task.getProcessInstanceId(), workflowAggregateId);
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

    executeUserTask(taskId, bpmnErrorCode, tolerateGoneTask, null);

  }

  /**
   * @param beforeExecute Runs after the user task was found and before it is completed
   *        or cancelled - writes the values the model reads next
   */
  private void executeUserTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask,
      final Runnable beforeExecute) {

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
    if (beforeExecute != null) {
      beforeExecute.run();
    }
    if (bpmnErrorCode == null) {
      taskService.complete(taskId);
    } else {
      // routes the workflow through an error boundary event on the user task
      taskService.handleBpmnError(taskId, bpmnErrorCode);
    }

  }

  /**
   * Writes the shared values at the execution of a user task - the variables
   * the flow behind the task reads.
   *
   * @param taskId The user task's ID
   * @param aggregatePersistence The aggregate's persistence
   * @param workflowAggregateId The aggregate's ID
   */
  private void refreshSharedValuesOfUserTask(
      final String taskId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var variables = sharedValues(
        aggregatePersistence,
        workflowAggregateId,
        workflowModuleId,
        bpmnProcessId);
    if (variables.isEmpty()) {
      return;
    }
    taskService.setVariables(taskId, variables);

  }

  @Override
  public void completeUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    // the non-advancing phase-one check - the completion happens after
    // the commit
    checkUserTaskExists(taskId, "completing");

  }

  @Override
  public void completeUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    executeUserTask(
        taskId,
        null,
        true,
        () -> refreshSharedValuesOfUserTask(
            taskId,
            aggregatePersistence,
            workflowAggregateId,
            workflowModuleId,
            bpmnProcessId));

  }

  @Override
  public void cancelUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    // the non-advancing phase-one check
    checkUserTaskExists(taskId, "canceling");

  }

  @Override
  public void cancelUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    executeUserTask(
        taskId,
        scopedIdentifier(workflowModuleId, bpmnErrorCode),
        true,
        () -> refreshSharedValuesOfUserTask(
            taskId,
            aggregatePersistence,
            workflowAggregateId,
            workflowModuleId,
            bpmnProcessId));

  }

  @Override
  public void completeTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    // the non-advancing phase-one check: does the task still exist? The
    // completion itself happens after the commit - doing it here would advance the
    // process although the transaction may still roll back, and a completion losing
    // a concurrency conflict could not be repeated
    checkTaskExists(taskId, "completing");

  }

  @Override
  public void completeTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    // the values the model reads are written along with the completion:
    // whatever the caller changed before answering the task decides where the
    // workflow goes next. Only after signalTask verified the task is still there -
    // writing variables of a gone execution would mark the dispatcher's transaction
    // rollback-only
    signalTask(
        taskId,
        null,
        true,
        () -> refreshSharedValues(
            taskId,
            aggregateForOperatorContext(aggregatePersistence, workflowAggregateId),
            workflowModuleId,
            bpmnProcessId));

  }

  @Override
  public void cancelTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    // the non-advancing phase-one check - the cancellation itself and
    // the values an operator sees along with it happen after the commit
    checkTaskExists(taskId, "canceling");

  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    // the values an operator sees in Cockpit are refreshed along with the
    // cancellation. It runs only once signalTask verified the task is still
    // there - writing variables of a gone execution would throw and mark the
    // dispatcher's transaction rollback-only
    signalTask(
        taskId,
        scopedIdentifier(workflowModuleId, bpmnErrorCode),
        true,
        () -> {
          final var workflowAggregate = aggregatePersistence.loadById(workflowAggregateId);
          if (workflowAggregate != null) {
            refreshSharedValues(taskId, workflowAggregate, workflowModuleId, bpmnProcessId);
          }
        });

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

  /**
   * The non-advancing phase-one check for a task of a {@code @TaskId} handler: the
   * parked execution has to be there, otherwise completing or cancelling it after the
   * commit could only be skipped. The core's election probe asks the same question
   * before electing this adapter - asking again inside the caller's transaction
   * catches a task which disappeared in between and aborts the transaction rather
   * than leaving an outbox entry which phase two can only skip.
   *
   * @param taskId The task (the parked execution's id)
   * @param operationDescription What was attempted, for the message
   */
  private void checkTaskExists(
      final String taskId,
      final String operationDescription) {

    final var exists = runtimeService
        .createExecutionQuery()
        .executionId(taskId)
        .count() > 0;
    if (!exists) {
      throw new IllegalStateException(
          """
              The task '%s' is gone (completed or canceled meanwhile) - aborting the transaction %s \
              it! VanillaBP progresses the workflow after the commit, so a task which no longer \
              exists cannot be reached any more."""
              .formatted(taskId, operationDescription));
    }

  }

  /**
   * The same check for a USER task, which lives in the task service rather than as a
   * parked execution.
   *
   * @param taskId The user task's id
   * @param operationDescription What was attempted, for the message
   */
  private void checkUserTaskExists(
      final String taskId,
      final String operationDescription) {

    final var exists = taskService
        .createTaskQuery()
        .taskId(taskId)
        .count() > 0;
    if (!exists) {
      throw new IllegalStateException(
          """
              The user task '%s' is gone (completed or canceled meanwhile) - aborting the \
              transaction %s it! VanillaBP progresses the workflow after the commit, so a task \
              which no longer exists cannot be reached any more."""
              .formatted(taskId, operationDescription));
    }

  }

  /**
   * Whether an execution of the given workflow waits for the message - the question
   * phase one asks before scheduling a correlation, and phase two asks again before
   * correlating (a subscription gone by dispatch time is the at-least-once residual).
   * <p>
   * The subscription carries the SCOPED message name, like the correlation itself:
   * querying the plain name would find nothing wherever a workflow module
   * prefixes its identifiers. A correlation id is deliberately NOT part of the
   * question - it selects among several waiting executions, which is the correlation's
   * job, not this check's.
   *
   * @param workflowModuleId The workflow module ID
   * @param messageName The message name as the application knows it
   * @param businessKey The workflow aggregate's ID
   * @return Whether at least one execution waits for that message
   */
  private boolean messageSubscriptionWaiting(
      final String workflowModuleId,
      final String messageName,
      final String businessKey) {

    return !waitingExecutions(workflowModuleId, messageName, businessKey).isEmpty();

  }

  /**
   * The executions waiting for a message of this workflow aggregate.
   *
   * @param workflowModuleId The workflow module ID
   * @param messageName The BPMN message name
   * @param businessKey The workflow aggregate's ID
   * @return The waiting executions (never <code>null</code>)
   */
  private java.util.List<org.camunda.bpm.engine.runtime.Execution> waitingExecutions(
      final String workflowModuleId,
      final String messageName,
      final String businessKey) {

    var query = runtimeService
        .createExecutionQuery()
        .messageEventSubscriptionName(scopedIdentifier(workflowModuleId, messageName))
        .processInstanceBusinessKey(businessKey);
    final var tenantId = tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    return query.list();

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

    // phase one does not advance the process - a signal reaching a
    // subscription IS progress, so it is broadcast after the commit
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
   * its identifiers. No variables travel, although every other sync point
   * writes them: a broadcast reaches workflows of
   * OTHER aggregates, so writing the values of the sending one into them would state
   * something false. Camunda 8 behaves the same way, for the same reason - the
   * difference to the other sync points is the broadcast, not the engine.
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

    // the non-advancing phase-one check: is a subscription waiting for
    // this message? Correlating itself continues the workflow and happens after the
    // commit - but "nothing matched" was a synchronous failure before this adapter
    // went two-phase, and it stays one. The embedded engine answers from the same
    // transaction, so the answer is exact
    final var businessKey = String.valueOf(aggregatePersistence.getAggregateId(workflowAggregate));
    if (!subscriptionWaitingFor(workflowModuleId, bpmnProcessId, messageName, businessKey, correlationId)) {
      throw new IllegalStateException(
          """
              No execution of workflow aggregate '%s' waits for message '%s' (BPMN process '%s' of \
              workflow module '%s')%s! Correlating it would do nothing - check the message name \
              against the model, and whether the workflow already passed the point where it waits."""
              .formatted(
                  businessKey,
                  messageName,
                  bpmnProcessId,
                  workflowModuleId,
                  correlationId == null
                      ? ""
                      : " with correlation id '%s' (the waiting execution expects the one stored in "
                          + "its local variable '%s')".formatted(
                              correlationId,
                              correlationIdVariableName(bpmnProcessId, messageName))));
    }

  }

  /**
   * The phase-one question of a correlation: does an execution wait for this
   * message - and, if the application named a correlation id, does one of those
   * executions expect exactly that id? Reading a local variable is what the
   * correlation itself matches on ({@code localVariableEquals}), so a wrong id fails
   * where the application passed it instead of behind the commit.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID (names the correlation-id variable)
   * @param messageName The BPMN message name
   * @param businessKey The workflow aggregate's ID
   * @param correlationId The expected correlation id or <code>null</code>
   * @return Whether correlating would find a subscription
   */
  private boolean subscriptionWaitingFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String businessKey,
      final String correlationId) {

    if (correlationId == null) {
      return messageSubscriptionWaiting(workflowModuleId, messageName, businessKey);
    }
    final var variableName = correlationIdVariableName(bpmnProcessId, messageName);
    return waitingExecutions(workflowModuleId, messageName, businessKey)
        .stream()
        .anyMatch(execution -> correlationId
            .equals(runtimeService.getVariableLocal(execution.getId(), variableName)));

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
    if (!messageSubscriptionWaiting(workflowModuleId, messageName, businessKey)) {
      log.warn(
          "Camunda7[{}]: no waiting subscription for message '{}' of workflow aggregate '{}' - "
              + "skipping the redelivered phase-two correlation",
          adapterId,
          messageName,
          businessKey);
      return;
    }
    // the values the model reads travel with the correlation: a gateway
    // behind the receiving event decides on what the caller changed before correlating
    messageCorrelation(workflowModuleId, bpmnProcessId, messageName, businessKey, correlationId)
        .setVariables(
            sharedValues(aggregatePersistence, workflowAggregateId, workflowModuleId, bpmnProcessId))
        .correlateWithResult();

  }

  @Override
  public void aggregateChangedPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    // phase one does not advance the process - writing here would show
    // values of a transaction which may still roll back, and re-evaluating
    // conditional events IS progress
  }

  @Override
  public void aggregateChangedPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    final var aggregate = aggregatePersistence.loadById(workflowAggregateId);
    pushAggregate(
        String.valueOf(workflowAggregateId),
        aggregate,
        taskId,
        true,
        workflowModuleId,
        bpmnProcessId);

  }

  /**
   * Writes the aggregate's shared values into the workflow (see
   * {@link #operatorContext}) and lets the engine re-evaluate what waits for a
   * change.
   * <p>
   * Camunda 7 evaluates conditional events when a variable of their scope changes,
   * so the write itself is the trigger - and it has to happen even for an aggregate
   * sharing nothing at all, which is why a technical marker variable is written
   * alongside. What a condition then reads are the values written here;
   * without the write nothing would look.
   *
   * @param businessKey The aggregate's ID
   * @param aggregate The workflow aggregate or <code>null</code>
   * @param taskId The parked execution whose scope receives the values, or
   *        <code>null</code> for the workflow's global scope
   * @param tolerateGoneWorkflow Whether a workflow gone by now is tolerated (phase
   *        two is at-least-once) instead of failing. Judged within the scope of the
   *        call - a foreign workflow carrying the same business key would otherwise
   *        make an ended workflow look present, and a redelivered push would write
   *        there instead of being skipped
   */
  private void pushAggregate(
      final String businessKey,
      final A aggregate,
      final String taskId,
      final boolean tolerateGoneWorkflow,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var variables = new java.util.LinkedHashMap<String, Object>(
        sharedValues(aggregate, workflowModuleId, bpmnProcessId));
    if (variables.isEmpty()) {
      // an aggregate sharing nothing at all (@NoSyncWithBPMS on the class) - without a
      // variable event the engine would not look at its conditional events, so a
      // technical marker is written instead
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
      // no scope comparison on this path, and that is a decision rather than an
      // omission: the caller names a task it was told about through
      // '@TaskId', and a Camunda 7 execution id is a UUID the engine hands out per
      // execution, so it addresses exactly one execution of one instance. That is
      // unlike Camunda 8, where a job or user-task KEY ignores the tenant and a
      // second adapter id on the same cluster can therefore be asked about a task of
      // its own. What the branch below cannot rely on is the business
      // key, which is why only it needs the filter.
      //
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

    // narrowed to the scope of the CALL: a business key is the
    // workflow-aggregate id, unique per aggregate type and not across an engine, so the
    // key alone also matches a workflow of another workflow module, of another adapter id
    // during a migration, or of another application sharing the database. Writing there
    // would not only report the wrong thing, it would ADVANCE that workflow, because a
    // variable write is what makes Camunda 7 re-evaluate conditional events - and this
    // method writes even for an aggregate sharing nothing (the marker above)
    final var candidates = within(
        runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(businessKey),
        io.vanillabp.integration.adapter.spi.WorkflowScope.of(workflowModuleId, bpmnProcessId))
        .list();
    if (candidates.size() > 1) {
      // one business key is one aggregate, so two instances of one scope carrying it is a
      // broken assumption rather than an ambiguity to resolve. It used to arrive as a
      // 'ProcessEngineException' from singleResult() naming neither the aggregate nor the
      // instances
      throw new IllegalStateException(
          ("Camunda7[%s]: aggregate '%s' of '%s/%s' is carried by %d workflows at once (%s)! A "
              + "workflow-aggregate id belongs to ONE workflow, so its changed values cannot be "
              + "pushed - check whether something started a second workflow with the same "
              + "aggregate.")
              .formatted(
                  adapterId,
                  businessKey,
                  workflowModuleId,
                  bpmnProcessId,
                  candidates.size(),
                  candidates
                      .stream()
                      .map(org.camunda.bpm.engine.runtime.ProcessInstance::getId)
                      .collect(java.util.stream.Collectors.joining(", "))));
    }
    final var processInstance = candidates.isEmpty()
        ? null
        : candidates.get(0);
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

    // the non-advancing phase-one check: is there a message start event
    // of this name? Starting itself happens after the commit, idempotently
    final var scopedMessageName = scopedIdentifier(workflowModuleId, messageName);
    final var startEventExists = runtimeService
        .createEventSubscriptionQuery()
        .eventType("message")
        .eventName(scopedMessageName)
        .count() > 0;
    if (!startEventExists) {
      throw new IllegalStateException(
          """
              No message start event named '%s' is deployed (BPMN process '%s' of workflow module \
              '%s')! Starting the workflow by that message would do nothing - check the message \
              name against the model."""
              .formatted(messageName, bpmnProcessId, workflowModuleId));
    }

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
    startByMessage(
        workflowModuleId,
        messageName,
        businessKey,
        sharedValues(aggregatePersistence, workflowAggregateId, workflowModuleId, bpmnProcessId));

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    // phase one does not advance the process: the instance is created
    // after the commit, so a rollback cannot leave a ghost instance behind. There
    // is nothing to validate against the engine either - starting is the degenerate
    // two-phase case, like on a remote BPMS
  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

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

    // the aggregate is committed by now, so its shared attributes can be written as
    // the operator context of the new instance - phase one had it at hand, phase two
    // has to read it back
    startProcessInstance(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        aggregateForOperatorContext(aggregatePersistence, workflowAggregateId));

  }

  /**
   * Reads the aggregate for the operator context of a workflow about to start. The
   * context is what an operator sees in Cockpit, so a persistence which cannot read
   * an aggregate by its ID must not keep the workflow from starting.
   *
   * @param aggregatePersistence The aggregate's persistence
   * @param workflowAggregateId The aggregate's ID
   * @return The aggregate or <code>null</code> if it cannot be read
   */
  private A aggregateForOperatorContext(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    if (aggregatePersistence == null) {
      return null;
    }
    try {
      return aggregatePersistence.loadById(workflowAggregateId);
    } catch (final RuntimeException e) {
      log.warn(
          "Camunda7[{}]: could not read the aggregate '{}' to write the operator context of "
              + "the workflow about to start - starting it without that context",
          adapterId,
          workflowAggregateId,
          e);
      return null;
    }

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
