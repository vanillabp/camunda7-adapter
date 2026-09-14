package io.vanillabp.camunda7.quarkus;

import java.util.List;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.expressions.ExprAggregate;
import io.vanillabp.camunda7.quarkus.expressions.ExprCustomer;
import io.vanillabp.camunda7.quarkus.expressions.ExprHiddenOrder;
import io.vanillabp.camunda7.quarkus.expressions.ExprItem;
import io.vanillabp.camunda7.quarkus.expressions.ExprOrder;
import io.vanillabp.camunda7.quarkus.expressions.ExprPersistence;
import io.vanillabp.camunda7.quarkus.expressions.ExprWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The startup check which reports BPMN expressions reading attributes the workflow aggregate
 * does not share, on Quarkus. An expression like this is the quiet kind of mistake: the
 * engine reads null, a gateway takes the other flow and a conditional event waits for good,
 * and nothing anywhere says why. So the check reads every expression of every deployed model
 * while the application boots and says per element where the path stops.
 * <p>
 * It is written once, in the platform-neutral core, and until now it ran on Spring Boot only.
 * A check nobody runs on a platform is a promise nobody kept there, which is the whole reason
 * this repository measures its coverage per platform.
 * <p>
 * The model deploys one process whose expressions stop in five different places, plus three
 * which read what is shared and have to stay unmentioned. Nothing of it ever runs: every
 * branch parks in a timer, and what is measured is the deployment.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7UnsharedExpressionCheckTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(ExprAggregate.class)
          .addClass(ExprCustomer.class)
          .addClass(ExprHiddenOrder.class)
          .addClass(ExprItem.class)
          .addClass(ExprOrder.class)
          .addClass(ExprPersistence.class)
          .addClass(ExprWorkflowService.class)
          .addAsResource("unshared-expressions/application.yaml", "application.yaml")
          .addAsResource(
              "c7-expressions/processes/expr-check.bpmn",
              "c7-expressions/processes/expr-check.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // what the check writes while the application boots is the result here, and a
      // prod-mode-style capture cannot reach it: the boot logs into a log context of its own
      .setLogRecordPredicate(record -> record
          .getLoggerName()
          .startsWith("io.vanillabp"))
      .assertLogRecords(Camunda7UnsharedExpressionCheckTest::theCheckReportedWhereEachPathStops);

  private static void theCheckReportedWhereEachPathStops(
      final List<LogRecord> records) {

    final var log = records
        .stream()
        .map(Camunda7UnsharedExpressionCheckTest::textOf)
        .toList();

    final var keptBack = about(log, "XC_yes_kept");
    Assertions.assertTrue(keptBack.contains("reads the path 'order.internalCode'"), () -> keptBack);
    Assertions
        .assertTrue(keptBack.contains("'internalCode' IS a readable attribute of 'ExprOrder'"), () -> keptBack);

    final var hidden = about(log, "XC_yes_hidden");
    Assertions
        .assertTrue(
            hidden.contains("reads 'hiddenOrder', which IS an attribute of the workflow aggregate"),
            () -> hidden);

    final var unknownAttribute = about(log, "XC_yes_unknown");
    Assertions
        .assertTrue(unknownAttribute.contains("'ExprCustomer' has no readable attribute 'address'"),
            () -> unknownAttribute);

    final var belowADate = about(log, "XC_Date");
    Assertions.assertTrue(belowADate.contains("reads the path 'order.dueDate.year'"), () -> belowADate);
    Assertions.assertTrue(belowADate.contains("LocalDate"), () -> belowADate);
    Assertions.assertTrue(belowADate.contains("carries nothing below it"), () -> belowADate);

    final var noCollection = about(log, "XC_Items");
    Assertions.assertTrue(noCollection.contains("'ExprOrder' has no readable attribute 'hiddenItems'"),
        () -> noCollection);
    Assertions.assertTrue(noCollection.contains("did not resolve to a collection"), () -> noCollection);

    // what is shared is what the application meant to share, and saying so per element would
    // make the report unreadable exactly where somebody has to read it
    final var everything = String.join("\n", log);
    Assertions
        .assertFalse(
            everything.contains("of element 'XC_yes_shared'"),
            () -> "an expression reading a shared attribute must not be reported: "
                + everything);

  }

  /**
   * The one reported line about one element.
   *
   * @param log Every line the adapter wrote while booting
   * @param elementId The element asked about
   * @return That line, or a failure naming what was there instead
   */
  private static String about(
      final List<String> log,
      final String elementId) {

    final var reported = log
        .stream()
        .filter(line -> line.contains("of element '"
            + elementId
            + "'"))
        .toList();
    Assertions
        .assertEquals(
            1,
            reported.size(),
            () -> "expected exactly one report about '%s' but the boot wrote:%n%s"
                .formatted(elementId, String.join("\n", log)));
    return reported.getFirst();

  }

  /**
   * What a record says, with its parameters filled in. The adapter logs through SLF4J, so a
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
  public void theCheckRunsWhileTheApplicationBoots() {
    // the assertions happen on the records of the boot (assertLogRecords above)
  }

}
