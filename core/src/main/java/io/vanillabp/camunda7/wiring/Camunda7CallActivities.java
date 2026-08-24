package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaIn;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import lombok.extern.slf4j.Slf4j;

/**
 * What a call activity needs on Camunda 7 to continue the SAME business case: the
 * business key.
 * <p>
 * VanillaBP keeps the workflow aggregate's ID in Camunda's business key. Camunda 7
 * does not pass the business key to a called process unless the model says so, so the
 * called process ran without one and the first task of it failed while resolving the
 * aggregate - with the persistence's own message, mentioning neither the call
 * activity nor the business key nor VanillaBP. Camunda 8 has no such gap
 * ({@code propagateAllParentVariables} carries the ID variable into the child), so
 * one engine behaved differently from the other for a model which looked fine.
 * <p>
 * The propagation is therefore injected while the BPMN is prepared for deployment -
 * the same pass which attaches listeners and rewrites called elements for
 * name-clash avoidance. It is injected only where it is right:
 * <ul>
 * <li>the called process is addressed statically (an expression addresses a process
 * VanillaBP does not know),</li>
 * <li>the called process works on the SAME workflow aggregate as the calling one
 * (a process with an aggregate of its own must NOT be handed the caller's identity -
 * it gets its own, e.g. through a {@code @WorkflowStartedByBpms} method),</li>
 * <li>the model does not pass a business key already - what the application
 * modelled wins.</li>
 * </ul>
 */
@Slf4j
public final class Camunda7CallActivities {

  /**
   * What Camunda 7 evaluates to the calling instance's business key.
   */
  static final String PARENT_BUSINESS_KEY_EXPRESSION = "#{execution.processBusinessKey}";

  private Camunda7CallActivities() {
    // utility class
  }

  /**
   * Injects the business-key propagation into the call activities of the given model
   * (see the class comment). Called BEFORE name-clash avoidance rewrites the called
   * elements, so the process IDs are the ones the application knows.
   *
   * @param model The BPMN model of one file
   * @param workflowModuleId The workflow module the file belongs to
   * @param workflowTaskInvoker The core, asked which processes share an aggregate
   */
  public static void propagateBusinessKey(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    model
        .getModelElementsByType(CallActivity.class)
        .forEach(callActivity -> {
          final var calledElement = callActivity.getCalledElement();
          if ((calledElement == null) || calledElement.isBlank() || calledElement.contains("${") || calledElement
              .contains("#{")) {
            return;
          }
          final var callingProcessId = processIdOf(callActivity);
          if (callingProcessId == null) {
            return;
          }
          if (!workflowTaskInvoker
              .workflowsShareTheWorkflowAggregate(workflowModuleId, callingProcessId, calledElement)) {
            return;
          }
          if (passesBusinessKeyAlready(callActivity)) {
            return;
          }
          final var camundaIn = model.newInstance(CamundaIn.class);
          camundaIn.setCamundaBusinessKey(PARENT_BUSINESS_KEY_EXPRESSION);
          extensionElementsOf(model, callActivity).addChildElement(camundaIn);
          log.debug(
              "Camunda7: call activity '{}' of BPMN process '{}' (workflow module '{}') passes the "
                  + "business key to '{}' - both work on the same workflow aggregate",
              callActivity.getId(),
              callingProcessId,
              workflowModuleId,
              calledElement);
        });

  }

  /**
   * The ID of the process a call activity belongs to (it may sit in a subprocess, so
   * the parents are walked).
   */
  private static String processIdOf(
      final CallActivity callActivity) {

    var current = callActivity.getParentElement();
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * Whether the model passes a business key to the called process already - an
   * application which modelled it keeps its own expression.
   */
  private static boolean passesBusinessKeyAlready(
      final CallActivity callActivity) {

    final var extensionElements = callActivity.getExtensionElements();
    if (extensionElements == null) {
      return false;
    }
    return extensionElements
        .getElementsQuery()
        .filterByType(CamundaIn.class)
        .list()
        .stream()
        .anyMatch(camundaIn -> (camundaIn.getCamundaBusinessKey() != null) && !camundaIn
            .getCamundaBusinessKey()
            .isBlank());

  }

  private static ExtensionElements extensionElementsOf(
      final BpmnModelInstance model,
      final CallActivity callActivity) {

    final var existing = callActivity.getExtensionElements();
    if (existing != null) {
      return existing;
    }
    final var extensionElements = model.newInstance(ExtensionElements.class);
    callActivity.setExtensionElements(extensionElements);
    return extensionElements;

  }

}
