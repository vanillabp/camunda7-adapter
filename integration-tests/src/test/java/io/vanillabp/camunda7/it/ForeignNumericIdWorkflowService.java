package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process whose workflow aggregate has a numeric id.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignNumericIdAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignNumericIdProcess"))
public class ForeignNumericIdWorkflowService {

  @WorkflowTask(taskDefinition = "recordForeignNumericIdStart")
  public void recordForeignNumericIdStart(
      final ForeignNumericIdAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignNumericIdStart");

  }

}
