package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the timer-start integration test. It has NO
 * <code>@WorkflowStartedByBpms</code> method on purpose: the aggregate of a
 * timer-started workflow comes into existence without any application code, and the
 * task following the start event has to find it.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TimerStartTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerStartProcess"))
public class TimerStartTestWorkflowService {

  /**
   * Story 43: the workflow started by the timer also reports its end.
   */
  @WorkflowEnded
  public void workflowEnded(
      final TimerStartTestAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs("%s/%s".formatted(end.kind(), end.endEventId()));

  }

  @WorkflowTask(taskDefinition = "recordStart")
  public void recordStart(
      final TimerStartTestAggregate aggregate) {

    aggregate.setProcessedBy("recordStart");

  }

}
