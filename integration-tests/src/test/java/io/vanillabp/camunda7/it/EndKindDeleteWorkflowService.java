package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow which waits an hour at a timer. The test does not let it get that far. It removes
 * the instance through the engine's API, the way an operator does in the Camunda web app. The
 * workflow exists to record what this engine calls that end.
 */
@Service
@WorkflowService(
    workflowAggregateClass = EndKindDeleteAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "EndKindDeleteProcess"))
public class EndKindDeleteWorkflowService {

  private final ProcessService<EndKindDeleteAggregate> processService;

  public EndKindDeleteWorkflowService(
      final ProcessService<EndKindDeleteAggregate> processService) {

    this.processService = processService;

  }

  public EndKindDeleteAggregate startWorkflow() {

    return processService.startWorkflow(new EndKindDeleteAggregate());

  }

  @WorkflowEnded
  public void workflowEnded(
      final EndKindDeleteAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs("%s/%s".formatted(end.kind(), end.endEventId()));

  }

}
