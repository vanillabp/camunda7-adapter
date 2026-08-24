package io.vanillabp.camunda7.deployment;

import java.util.List;
import java.util.stream.Stream;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;

/**
 * Finds the elements of a BPMN process which can put a SECOND token into a running
 * workflow. The core is told about them during wiring and decides what it
 * means: two tokens are two branches writing the same workflow aggregate, and an
 * aggregate without a version attribute loses one of the two writes without any error.
 * <p>
 * What is looked for - each one verified against this engine rather than taken from a
 * list:
 * <ul>
 * <li>a boundary event which does NOT cancel its activity, so its branch runs next to
 * the still open activity;</li>
 * <li>a parallel or inclusive gateway forking into more than one sequence flow (one
 * outgoing flow is a joining or pass-through gateway, which forks nothing);</li>
 * <li>an activity marked as a PARALLEL multi-instance (a sequential one holds one
 * token at a time);</li>
 * <li>an event subprocess whose start event does not interrupt the process.</li>
 * </ul>
 */
public class Camunda7ConcurrentTokens {

  private Camunda7ConcurrentTokens() {
  }

  /**
   * The IDs of the elements of the given BPMN process which can produce a second
   * token.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process' ID as the model knows it (the SCOPED ID)
   * @return The element IDs, possibly empty
   */
  public static List<String> elementIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return Stream
        .of(
            elementsOf(model, bpmnProcessId, BoundaryEvent.class)
                .filter(boundaryEvent -> !boundaryEvent.cancelActivity()),
            elementsOf(model, bpmnProcessId, ParallelGateway.class)
                .filter(gateway -> gateway.getOutgoing().size() > 1),
            elementsOf(model, bpmnProcessId, InclusiveGateway.class)
                .filter(gateway -> gateway.getOutgoing().size() > 1),
            elementsOf(model, bpmnProcessId, Activity.class)
                .filter(Camunda7ConcurrentTokens::isParallelMultiInstance),
            elementsOf(model, bpmnProcessId, SubProcess.class)
                .filter(Camunda7ConcurrentTokens::isNonInterruptingEventSubProcess))
        .flatMap(elements -> elements)
        .map(FlowElement::getId)
        .distinct()
        .toList();

  }

  private static <T extends FlowElement> Stream<T> elementsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Class<T> type) {

    return model
        .getModelElementsByType(type)
        .stream()
        .filter(element -> bpmnProcessId.equals(Camunda7DeploymentService.owningProcessId(element)));

  }

  private static boolean isParallelMultiInstance(
      final Activity activity) {

    return (activity.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop) && !loop.isSequential();

  }

  private static boolean isNonInterruptingEventSubProcess(
      final SubProcess subProcess) {

    return subProcess.triggeredByEvent() && subProcess
        .getChildElementsByType(StartEvent.class)
        .stream()
        .anyMatch(startEvent -> !startEvent.isInterrupting());

  }

}
