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

  @WorkflowStartedByBpms
  public void startedByBpms(
      final DeclaredRuntimeAggregate aggregate,
      final BpmsStartTrigger trigger) {

    aggregate.setStartedAs(trigger.kind().name());
    aggregate.setSignalName(trigger.signalName());

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
