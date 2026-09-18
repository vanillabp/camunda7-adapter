package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the condition-started process of the foreign-start integration
 * test. It has no <code>@WorkflowStartedByBpms</code> method: the aggregate of a workflow
 * the application did not start comes into existence without application code.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignConditionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignConditionProcess"))
public class ForeignConditionWorkflowService {

  @WorkflowTask(taskDefinition = "recordForeignConditionStart")
  public void recordForeignConditionStart(
      final ForeignConditionAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignConditionStart");

  }

}
