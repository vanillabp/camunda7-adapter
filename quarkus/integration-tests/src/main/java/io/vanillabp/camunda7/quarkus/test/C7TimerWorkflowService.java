package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the timer-started workflow. It has NO method starting
 * anything on purpose: the aggregate of a timer-started workflow comes into existence
 * without any application code, and the task following the start event has to find
 * it.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C7TimerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerStartProcess"))
public class C7TimerWorkflowService {

  /**
   * Story 43: the workflow started by the timer also reports its end.
   *
   * @param aggregate The workflow aggregate
   * @param end How the workflow ended
   */
  @WorkflowEnded
  public void workflowEnded(
      final C7TimerAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs("%s/%s".formatted(end.kind(), end.endEventId()));

  }

  @WorkflowTask(taskDefinition = "recordStart")
  public void recordStart(
      final C7TimerAggregate aggregate) {

    aggregate.setProcessedBy("recordStart");

  }

}
