package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the task-processing integration tests (story 21b): one
 * {@code @WorkflowTask} method per outcome/binding variation, serving FIVE BPMN
 * processes of one aggregate ({@code secondaryBpmnProcesses} - exercised for real
 * on Camunda 7).
 */
@Service
@WorkflowService(
    workflowAggregateClass = TaskTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "ErrorProcess"), @BpmnProcess(bpmnProcessId = "FailProcess"), @BpmnProcess(
            bpmnProcessId = "AsyncProcess"), @BpmnProcess(bpmnProcessId = "MultiInstanceProcess")
    })
public class TaskTestWorkflowService {

  private final ProcessService<TaskTestAggregate> processService;

  public TaskTestWorkflowService(
      final ProcessService<TaskTestAggregate> processService) {

    this.processService = processService;

  }

  public TaskTestAggregate startTaskProcess(
      final TaskTestAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void happyTask(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("happy");

  }

  @WorkflowTask
  public void afterApproval(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("approved");

  }

  @WorkflowTask
  public void errorTask(
      final TaskTestAggregate aggregate) {

    // the mutation has to be COMMITTED although the handler throws - the V1
    // TaskException contract (BPMN error, no rollback)
    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void errorHandled(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("handled");

  }

  @WorkflowTask
  public void alwaysFails(
      final TaskTestAggregate aggregate) {

    // must NEVER become visible: a technical exception rolls back the job
    // transaction including this mutation
    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-21b");

  }

  @WorkflowTask
  public void asyncTask(
      final TaskTestAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

  @WorkflowTask
  public void miTask(
      final TaskTestAggregate aggregate,
      @MultiInstanceIndex("MI_Task") final int index,
      @MultiInstanceTotal("MI_Task") final int total,
      @MultiInstanceElement("MI_Task") final Object element) {

    aggregate.appendResult("%s%d/%d".formatted(element, index, total));

  }

}
