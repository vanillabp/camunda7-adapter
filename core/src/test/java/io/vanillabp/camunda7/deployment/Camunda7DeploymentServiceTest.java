package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the Camunda 7 adapter contributes to the name-clash-avoidance model (story
 * 35): the mode applying without configuration, and how an unscoped workflow module
 * is reported - the alternatives to {@link NameClashAvoidance#NONE} are Camunda 7's,
 * so the message is the adapter's.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DeploymentServiceTest {

  private static final String MODULE = "loan-approval";

  private static Camunda7DeploymentService serviceOfAdapterId(
      final String adapterId) {

    return new Camunda7DeploymentService(adapterId, null, null, null, null);

  }

  @Test
  @DisplayName("Without configuration the mode is BY_ADAPTER - the engine is multi-tenant out of the box")
  public void defaultsToByAdapter() {

    assertEquals(
        NameClashAvoidance.BY_ADAPTER,
        serviceOfAdapterId("camunda7").defaultNameClashAvoidance(),
        "version 1's behavior: a tenant per workflow module");

  }

  /**
   * The WARNs the adapter logged (the module's logback-test.xml has no appender on
   * purpose).
   */
  private static List<String> warningsOf(
      final Runnable action) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(Camunda7DeploymentService.class);
    adapterLog.addAppender(logWatcher);
    try {
      action.run();
    } finally {
      adapterLog.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @Test
  @DisplayName("An unscoped workflow module is reported naming Camunda 7's alternatives")
  public void unscopedIdentifiersAreReported() {

    final var service = serviceOfAdapterId("myengine");

    final var configured = warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, false));
    assertEquals(1, configured.size(), () -> configured.toString());
    final var message = configured.getFirst();
    assertTrue(message.contains("'"
        + MODULE
        + "'"), () -> message);
    assertFalse(message.contains("nothing is configured"), () -> message);
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: by-adapter"),
        () -> message);
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: use-prefix"),
        () -> message);
    assertTrue(message.contains("data-source-name"), () -> message);
    assertTrue(message.contains("table-prefix"), () -> message);

    // the mode being the adapter's default is worth saying - the developer configured
    // nothing, so they may not know the mode at all
    final var byDefault = warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, true));
    assertTrue(byDefault.getFirst().contains("nothing is configured"), () -> byDefault.toString());

  }

}
