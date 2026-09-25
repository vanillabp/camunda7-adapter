package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application BEFORE the rename: it deploys the process the engine starts on its
 * own. Its only job in the test is to leave that deployment - and the engine's
 * repeating timer - behind.
 */
@Service
@Profile("decl-before")
@WorkflowService(
    workflowAggregateClass = DeclaredRuntimeAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DeclaredRuntimeOld"))
public class DeclaredRuntimeBeforeWorkflowService {

  /**
   * Builds the workflow aggregate of a workflow the engine started under this id.
   *
   * @param trigger What the engine fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public DeclaredRuntimeAggregate startedByBpms(
      final BpmsStartTrigger trigger) {

    final var aggregate = new DeclaredRuntimeAggregate();
    // the trigger time as the id, which is what the test reads back to tell the
    // workflows of the two generations apart
    aggregate.setId(trigger.time().toString());
    aggregate.setStartedAs(trigger.kind().name());
    aggregate.setSignalName(trigger.signalName());
    return aggregate;

  }

  @WorkflowTask(taskDefinition = "declaredRuntimeTask")
  public void declaredRuntimeTask(
      final DeclaredRuntimeAggregate aggregate) {

    aggregate.setProcessedBy("before-the-rename");

  }

  @io.vanillabp.spi.service.WorkflowEnded
  public void workflowEnded(
      final DeclaredRuntimeAggregate aggregate,
      final io.vanillabp.spi.service.WorkflowEnd end) {

    aggregate.setEndedAs("%s/before".formatted(end.kind()));

  }

}
