package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the timer-started process of the foreign-start integration
 * test. It has no <code>@WorkflowStartedByBpms</code> method: the aggregate of a workflow
 * the application did not start comes into existence without application code.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignTimerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignTimerProcess"))
public class ForeignTimerWorkflowService {

  private final ProcessService<ForeignTimerAggregate> processService;

  public ForeignTimerWorkflowService(
      final ProcessService<ForeignTimerAggregate> processService) {

    this.processService = processService;

  }

  /**
   * Starts the workflow the way an application does it, although the process is started
   * by a timer as well. VanillaBP writes the aggregate and then the instance, both in
   * this transaction, and the business key it sets is the aggregate's id.
   *
   * @param id The id of the workflow aggregate to start
   */
  @Transactional
  public void startTheWorkflow(
      final String id) {

    final var aggregate = new ForeignTimerAggregate();
    aggregate.setId(id);
    aggregate.setStartedBy("the application");
    processService.startWorkflow(aggregate);

  }

  @WorkflowTask(taskDefinition = "recordForeignTimerStart")
  public void recordForeignTimerStart(
      final ForeignTimerAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignTimerStart");

  }

}
