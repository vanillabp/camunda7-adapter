package io.vanillabp.camunda7.processservice;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import lombok.RequiredArgsConstructor;

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
 * This is the Version-2 skeleton: only the identity method and the two-phase-commit flag
 * are implemented. The runtime methods deliberately throw
 * {@link UnsupportedOperationException} - they are implemented in later stories and must
 * never silently do nothing.
 */
@RequiredArgsConstructor
public class Camunda7ProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

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
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    throw new UnsupportedOperationException("startWorkflowPhaseOne is implemented in a later story");

  }

  @Override
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    throw new UnsupportedOperationException("startWorkflowPhaseTwo is implemented in a later story");

  }

}
