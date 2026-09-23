package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.util.xml.Element;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Tells which start events the engine fires on its own. Two callers need that, from
 * two angles: the deployment service reads the parsed BPMN model to report them to
 * the core, and the parse listener sees the raw XML element while the engine builds
 * its process definition.
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

}
