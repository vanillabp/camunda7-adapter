package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application after the upgrade from version 1. Its workflows were started by version 1,
 * and it has to serve them to their end. Both processes work on one aggregate, so the called
 * process continues the business case of its caller.
 * <p>
 * It is a bean only under its own profile, because the other boots of this module deploy
 * other models.
 */
@Service
@Profile("upgrade-from-v1")
@WorkflowService(
    workflowAggregateClass = UpgradeFromV1Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UpgradeFromV1Process"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "UpgradeFromV1Child"))
public class UpgradeFromV1WorkflowService {

  private final ProcessService<UpgradeFromV1Aggregate> processService;

  private final UpgradeFromV1Repository repository;

  public UpgradeFromV1WorkflowService(
      final ProcessService<UpgradeFromV1Aggregate> processService,
      final UpgradeFromV1Repository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public void completeReview(
      final String id,
      final String taskId) {

    processService.completeUserTask(repository.findById(id).orElseThrow(), taskId);

  }

  public void cancelReview(
      final String id) {

    processService.correlateMessage(repository.findById(id).orElseThrow(), "UpgradeFromV1Cancel");

  }

  @WorkflowTask(taskDefinition = "upgradeFromV1Review")
  public void upgradeFromV1Review(
      final UpgradeFromV1Aggregate aggregate,
      @TaskEvent final TaskEvent.Event event) {

    aggregate.addReviewEvent(event.name());

  }

  @WorkflowTask(taskDefinition = "upgradeFromV1Calc")
  public void upgradeFromV1Calc(
      final UpgradeFromV1Aggregate aggregate) {

    aggregate.setCalculated(true);

  }

  @WorkflowTask(taskDefinition = "upgradeFromV1ChildTask")
  public void upgradeFromV1ChildTask(
      final UpgradeFromV1Aggregate aggregate) {

    aggregate.addIteration("ran");

  }

  @WorkflowEnded
  public void workflowEnded(
      final UpgradeFromV1Aggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAt(end.endEventId());

  }

}
