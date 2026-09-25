package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the timer-started workflow. The engine starts it, so the
 * aggregate is built here and nowhere else, and the task following the start event has
 * to find it.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C7TimerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerStartProcess"))
public class C7TimerWorkflowService {

  /**
   * Builds the workflow aggregate of the workflow the timer started.
   *
   * @param trigger What the engine fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public C7TimerAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger) {

    final var aggregate = new C7TimerAggregate();
    // the trigger time as the id: the same firing reported twice finds this aggregate
    // instead of building a second one
    aggregate.setId(trigger.time().toString());
    return aggregate;

  }

  /**
   * The workflow started by the timer also reports its end.
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
