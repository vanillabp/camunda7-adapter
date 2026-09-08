package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The same application AFTER the rename: the BPMN process carries the new id, the old
 * one is declared as a secondary process - and the ENGINE keeps starting workflows
 * under the old id, by the repeating timer of the model it still holds and by its
 * signal subscription. Every one of them has to reach these methods: the aggregate
 * built by {@code @WorkflowStartedByBpms}, the task, and the end notification.
 */
@Service
@Profile("decl-after")
@WorkflowService(
    workflowAggregateClass = DeclaredRuntimeAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DeclaredRuntimeNew"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "DeclaredRuntimeOld"))
public class DeclaredRuntimeAfterWorkflowService {

  private final ProcessService<DeclaredRuntimeAggregate> processService;

  public DeclaredRuntimeAfterWorkflowService(
      final ProcessService<DeclaredRuntimeAggregate> processService) {

    this.processService = processService;

  }

  /**
   * Broadcasts the OLD generation's signal: only the model the engine holds under the
   * old id subscribes to it, so the workflow it starts runs under the declared-only
   * id.
   */
  public void fireTheOldSignal() {

    processService.sendSignal("DeclRuntimeSignal");

  }

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

    aggregate.setProcessedBy("after-the-rename");

  }

  @io.vanillabp.spi.service.WorkflowEnded
  public void workflowEnded(
      final DeclaredRuntimeAggregate aggregate,
      final io.vanillabp.spi.service.WorkflowEnd end) {

    aggregate.setEndedAs("%s/after".formatted(end.kind()));

  }

}
