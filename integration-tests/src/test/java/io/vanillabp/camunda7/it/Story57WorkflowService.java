package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the old-process-versions test (story 57). Its three methods
 * are the three states an application can be in towards the versions its BPMS holds:
 * <ul>
 * <li>{@code story57Kept} serves every version - the task survived into the deployed
 * model;</li>
 * <li>{@code story57Gone} serves version 1 only - the task was dropped from the model,
 * and the method stays for the workflows still running on that version. It matches no
 * task of the deployed model, which must not fail the start;</li>
 * <li>{@code story57Never} names a version this engine does not hold, so it never runs
 * and the task it would serve is unserved in version 1.</li>
 * </ul>
 */
@Service
@WorkflowService(
    workflowAggregateClass = Story57Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "Story57Process"))
public class Story57WorkflowService {

  private final ProcessService<Story57Aggregate> processService;

  public Story57WorkflowService(
      final ProcessService<Story57Aggregate> processService) {

    this.processService = processService;

  }

  public Story57Aggregate startWorkflow() {

    return processService.startWorkflow(new Story57Aggregate());

  }

  @WorkflowTask(taskDefinition = "story57Kept")
  public void story57Kept(
      final Story57Aggregate aggregate) {

    aggregate.setServedBy("kept");

  }

  @WorkflowTask(taskDefinition = "story57Gone", version = "1")
  public void story57Gone(
      final Story57Aggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setOpenTaskId(taskId);

  }

  @WorkflowTask(taskDefinition = "story57Never", version = "0")
  public void story57Never(
      final Story57Aggregate aggregate) {

    aggregate.setServedBy("never");

  }

}
