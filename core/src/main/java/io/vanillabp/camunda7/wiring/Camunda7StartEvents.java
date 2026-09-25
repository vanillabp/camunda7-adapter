package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.StartEvent;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Tells which start events the engine fires on its own, and which of them start a
 * WORKFLOW. Two callers need that, from two angles: the deployment service reads the
 * parsed BPMN model to report them to the core, and the parse listener sees the raw XML
 * element while the engine builds its process definition.
 */
public final class Camunda7StartEvents {

  private Camunda7StartEvents() {
  }

  /**
   * Which trigger a start event carries, read from the model while the engine parses it.
   * A none or message start event is answered with <code>null</code>: those are started by
   * the application, so there is nothing for the core to be told about.
   *
   * @param startEventElement The start event's XML element as the engine's parser
   *          sees it
   * @return Which kind of start event it is, or <code>null</code> if the engine does
   *         not fire it on its own (a none or message start event)
   */
  public static BpmsStartTrigger.Kind kindOf(
      final Element startEventElement) {

    if (startEventElement.element("timerEventDefinition") != null) {
      return BpmsStartTrigger.Kind.TIMER;
    }
    if (startEventElement.element("signalEventDefinition") != null) {
      return BpmsStartTrigger.Kind.SIGNAL;
    }
    if (startEventElement.element("conditionalEventDefinition") != null) {
      return BpmsStartTrigger.Kind.CONDITIONAL;
    }
    return null;

  }

  /**
   * Whether the start event of a parsed model starts the WORKFLOW, which is true of the
   * start events the process itself holds and of no other.
   * <p>
   * A start event of an event subprocess fires while the workflow already runs and
   * already has its aggregate, so it starts no workflow. Counting it as one would refuse
   * every such model at startup, because an application has to serve each start of a
   * workflow with a <code>&#64;WorkflowStartedByBpms</code> method, and would ask the
   * application at runtime to build a second aggregate for a workflow it already owns one
   * for.
   *
   * @param startEvent The start event as the BPMN model carries it
   * @return Whether the process itself holds it
   */
  public static boolean startsTheWorkflow(
      final StartEvent startEvent) {

    return startEvent.getParentElement() instanceof Process;

  }

  /**
   * Whether the start event the engine is parsing starts the WORKFLOW, read from the
   * scope the engine hands to its parse listeners.
   * <p>
   * The engine parses the start events of an event subprocess in the scope of that
   * subprocess and the start events of the process in the scope of the process
   * definition, and it draws the same line itself: a start event whose scope is no process
   * definition becomes a scope start event.
   *
   * @param scope The scope the engine parses the start event in
   * @return Whether that scope is the process itself
   */
  public static boolean startsTheWorkflow(
      final ScopeImpl scope) {

    return scope instanceof ProcessDefinitionEntity;

  }

}
