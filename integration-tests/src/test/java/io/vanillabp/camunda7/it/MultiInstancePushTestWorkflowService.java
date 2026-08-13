package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the multi-instance half of the aggregateChanged
 * integration test (story 44).
 */
@Service
@WorkflowService(
    workflowAggregateClass = MultiInstancePushTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AggregateChangedMultiInstanceProcess"))
public class MultiInstancePushTestWorkflowService {

  private final ProcessService<MultiInstancePushTestAggregate> processService;

  private final MultiInstancePushTestRepository repository;

  public MultiInstancePushTestWorkflowService(
      final ProcessService<MultiInstancePushTestAggregate> processService,
      final MultiInstancePushTestRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public MultiInstancePushTestAggregate startWorkflow() {

    return processService.startWorkflow(new MultiInstancePushTestAggregate());

  }

  /**
   * Pushes the aggregate into the scope of ONE task instance.
   *
   * @param aggregateId The aggregate's id
   * @param taskId The parked execution of that instance
   */
  public void pushInto(
      final Long aggregateId,
      final String taskId) {

    processService.aggregateChanged(repository.findById(aggregateId).orElseThrow(), taskId);

  }

  /**
   * Pushes the aggregate at the workflow's global scope.
   *
   * @param aggregateId The aggregate's id
   */
  public void pushGlobally(
      final Long aggregateId) {

    processService.aggregateChanged(repository.findById(aggregateId).orElseThrow());

  }

  @WorkflowTask
  public void multiInstanceTask(
      final MultiInstancePushTestAggregate aggregate,
      @TaskId final String taskId) {

    // parks this instance: the test pushes into its scope afterwards
    aggregate
        .setTaskIds(
            aggregate.getTaskIds() == null
                ? taskId
                : aggregate.getTaskIds()
                    + ","
                    + taskId);

  }

}
