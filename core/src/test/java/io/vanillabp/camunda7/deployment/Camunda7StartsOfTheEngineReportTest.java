package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about the start events which the engine fires on its own.
 * <p>
 * Every start event of a process goes to the core, the plain one included. But the line in
 * the log names only the start events which the engine fires itself. A plain start event
 * fires only when somebody asks for a start, and a message start event only when somebody
 * sends the message. So a process which has nothing else gets no such line at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StartsOfTheEngineReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String STARTED_BY_THE_BPMS = "is started by the BPMS itself";

  /**
   * A process which the application starts, the way {@code module-single} draws it.
   */
  private static final String A_PLAIN_START = """
          <bpmn:startEvent id="Event_Requested">
            <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Event_Requested" targetRef="Event_Done" />
          <bpmn:endEvent id="Event_Done">
            <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
          </bpmn:endEvent>
      """;

  /**
   * The same process, which the engine also starts every night.
   */
  private static final String A_PLAIN_START_AND_A_TIMER = A_PLAIN_START + """
          <bpmn:startEvent id="Event_Nightly">
            <bpmn:outgoing>Flow_NightlyToEnd</bpmn:outgoing>
            <bpmn:timerEventDefinition id="Timer_Nightly">
              <bpmn:timeCycle>R/PT24H</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="Flow_NightlyToEnd" sourceRef="Event_Nightly" targetRef="Event_Done" />
      """;

  /**
   * The same process, which an application can also start by a message. The message is
   * declared at the level of the definitions, so it is passed in separately.
   */
  private static final String A_PLAIN_START_AND_A_MESSAGE = A_PLAIN_START + """
          <bpmn:startEvent id="Event_Applied">
            <bpmn:outgoing>Flow_AppliedToEnd</bpmn:outgoing>
            <bpmn:messageEventDefinition id="Definition_Applied" messageRef="Message_Applied" />
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="Flow_AppliedToEnd" sourceRef="Event_Applied" targetRef="Event_Done" />
      """;

  private static final String THE_MESSAGE = """
          <bpmn:message id="Message_Applied" name="LoanApplied" />
      """;

  @Test
  @DisplayName("A process with a plain start event only is not called started by the BPMS")
  public void aPlainStartIsNoStartOfTheEngine() {

    final var said = infosAboutStartsOfTheEngine(A_PLAIN_START);

    assertEquals(
        List.of(),
        said,
        () -> "nothing but the application starts this process: "
            + said);

  }

  @Test
  @DisplayName("Next to a timer, the plain start event is left out of the line about starts of the BPMS")
  public void onlyTheTimerIsNamed() {

    final var said = infosAboutStartsOfTheEngine(A_PLAIN_START_AND_A_TIMER);

    assertEquals(
        List.of("Camunda7[c7]: BPMN process '"
            + PROCESS
            + "' (workflow module '"
            + MODULE
            + "') "
            + STARTED_BY_THE_BPMS
            + ": [BpmsInitiatedStartSpec[elementId=Event_Nightly, kind=TIMER, signalName=null]]"),
        said,
        () -> "the timer is the only start the engine fires on its own: "
            + said);

  }

  @Test
  @DisplayName("A process with a message start event is not called started by the BPMS")
  public void aMessageStartIsNoStartOfTheEngine() {

    final var said = infosAboutStartsOfTheEngine(A_PLAIN_START_AND_A_MESSAGE, THE_MESSAGE);

    assertEquals(
        List.of(),
        said,
        () -> "the application starts this process, by a call or by a message: "
            + said);

  }

  @Test
  @DisplayName("Next to a message start event, only the timer is named")
  public void aMessageStartIsLeftOutNextToATimer() {

    final var said = infosAboutStartsOfTheEngine(
        A_PLAIN_START_AND_A_TIMER + A_PLAIN_START_AND_A_MESSAGE.substring(A_PLAIN_START.length()),
        THE_MESSAGE);

    assertEquals(
        List.of("Camunda7[c7]: BPMN process '"
            + PROCESS
            + "' (workflow module '"
            + MODULE
            + "') "
            + STARTED_BY_THE_BPMS
            + ": [BpmsInitiatedStartSpec[elementId=Event_Nightly, kind=TIMER, signalName=null]]"),
        said,
        () -> "the timer is the only start the engine fires on its own: "
            + said);

  }

  private static List<String> infosAboutStartsOfTheEngine(
      final String processContent) {

    return infosAboutStartsOfTheEngine(processContent, "");

  }

  /**
   * Wires a process with the given elements and collects what the adapter logs about the
   * starts of the engine. The appender sits on the adapter's own logger, so the list is this
   * test's own.
   *
   * @param processContent The elements of the process to wire
   * @param definitions The elements next to the process, like the messages it refers to
   * @return The log lines about starts of the engine, in the order they were written
   */
  private static List<String> infosAboutStartsOfTheEngine(
      final String processContent,
      final String definitions) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        %s
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent, definitions);
    final var service = new Camunda7DeploymentService(
        "c7", null, mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .build(), new Camunda7TaskRegistry());
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(Camunda7DeploymentService.class);
    adapterLog.addAppender(logWatcher);
    try {
      final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
      service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    } finally {
      adapterLog.detachAppender(logWatcher);
      logWatcher.stop();
    }
    return logWatcher.list
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains(STARTED_BY_THE_BPMS))
        .toList();

  }

}
