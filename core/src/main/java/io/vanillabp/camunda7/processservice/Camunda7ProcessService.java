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
 * Camunda 7 runs embedded in the application's JVM and shares the same database and the
 * same transaction as the business code. Therefore
 * {@link #needsTwoPhaseCommitForStartingWorkflows()} returns {@code false}: starting a
 * workflow happens completely in phase one within the local transaction; phase two is a
 * no-op and no outbox is involved.
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

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    // Camunda 7 is embedded and joins the local transaction - everything happens in
    // phase one, so no two-phase commit / outbox is required.
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

    if (runtimeService == null) {
      throw new IllegalStateException(
          ("Camunda7[%s]: cannot start workflow '%s' - no embedded engine is available! A Camunda 7 "
              + "adapter requires a data source and a transaction manager so the embedded engine can "
              + "be wired.").formatted(adapterId, bpmnProcessId));
    }

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

    throw new UnsupportedOperationException("awarenessOfTask is implemented in a later story");

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final Object workflowAggregateId) {

    throw new UnsupportedOperationException("awarenessOfWorkflow is implemented in a later story");

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

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
      final Object workflowAggregateId) {

    // Camunda 7 starts the workflow entirely in phase one (needsTwoPhaseCommit... == false),
    // so the core never schedules phase two. A call here indicates a wiring problem.
    log.warn(
        "Camunda7[{}]: startWorkflowPhaseTwo was called for workflow '{}' of module '{}' (aggregate "
            + "'{}') although Camunda 7 starts workflows in phase one - ignoring (this should never "
            + "happen).",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

  }

}
