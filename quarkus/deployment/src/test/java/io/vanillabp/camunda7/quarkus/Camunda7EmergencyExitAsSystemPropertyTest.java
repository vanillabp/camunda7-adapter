package io.vanillabp.camunda7.quarkus;

import java.util.List;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.camunda7.wiring.SuspendedProcessDefinitions;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The emergency exit for suspended process definitions, set as a system property. Quarkus
 * ends the start for a key below <code>vanillabp</code> which no mapping knows. So the
 * adapter's overlay maps the key, and the start goes on and says that the exit was taken.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7EmergencyExitAsSystemPropertyTest {

  static {
    // set before the extension below builds and starts the application
    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "true");
  }

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("emergency-exit/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record
          .getLoggerName()
          .startsWith("io.vanillabp"))
      .assertLogRecords(Camunda7EmergencyExitAsSystemPropertyTest::theStartSaysTheExitWasTaken);

  @AfterAll
  static void takeTheSwitchOutAgain() {

    System.clearProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY);

  }

  private static void theStartSaysTheExitWasTaken(
      final List<LogRecord> records) {

    final var log = String.join("\n", records
        .stream()
        .map(Camunda7EmergencyExitAsSystemPropertyTest::textOf)
        .toList());
    Assertions.assertTrue(
        log.contains(
            "the system property '%s' is set to 'true'".formatted(SuspendedProcessDefinitions.IGNORE_PROPERTY)),
        () -> "expected the warning about the emergency exit but the boot wrote:\n"
            + log);
    Assertions.assertTrue(log.contains("emergency exit for one start"), log);

  }

  /**
   * What a record says, with its parameters filled in. The adapter logs through SLF4J. So a
   * record still carries the pattern and the values apart.
   */
  private static String textOf(
      final LogRecord record) {

    var text = String.valueOf(record.getMessage());
    final var parameters = record.getParameters();
    if (parameters != null) {
      for (final var parameter : parameters) {
        text = text.replaceFirst("\\{}", Matcher.quoteReplacement(String.valueOf(parameter)));
      }
    }
    return text;

  }

  @Test
  public void theStartGoesOnWithTheSwitchSet() {
    // the assertions happen on the records of the boot (assertLogRecords above)
  }

}
