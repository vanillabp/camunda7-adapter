package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.history.HistoryLevel;
import org.camunda.bpm.engine.impl.history.event.HistoryEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the key <code>history-level</code> does to a real engine on H2. The default is the
 * level of version 1, an unknown level and a level which differs from the database end the
 * start with a message which names the key, and <code>auto</code> takes the stored level. The
 * same on a booted application is {@code Camunda7VersionOneDatabaseIT}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7HistoryLevelTest {

  /** The sentence of the message about an unknown level which names the allowed ones. */
  private static final String NAMES_THE_LEVELS = "Use one of: none, activity, audit, full, auto";

  @Test
  @DisplayName("the default is 'full', the level of version 1, and the engine stores it")
  public void theDefaultIsFull() {

    assertEquals("full", new Camunda7EngineProperties().getHistoryLevel());

    final var engine = build("c7", "history-default", new Camunda7EngineProperties());
    try {
      assertEquals(HistoryLevel.HISTORY_LEVEL_FULL, configurationOf(engine).getHistoryLevel());
      assertEquals("3", storedLevel(engine));
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("a database set up with 'full' does not start with 'audit', and the message names the key and both levels")
  public void aDifferentLevelIsExplained() {

    final var first = build("c7", "history-mismatch", new Camunda7EngineProperties());
    try {
      final var audit = properties("audit");

      final var failure = assertThrows(IllegalStateException.class, () -> build("c7", "history-mismatch", audit));

      final var message = failure.getMessage();
      assertTrue(message.startsWith("Camunda 7 adapter 'c7' could not start its engine"), message);
      assertTrue(message.contains("The database stores 'full', but 'vanillabp.adapters.c7.history-level' is 'audit'"),
          message);
      assertTrue(message.contains("Set 'vanillabp.adapters.c7.history-level: full'"), message);
      // the two parts a failure analyzer shows under "Description" and "Action"
      final var explanation = assertInstanceOf(Camunda7StartupExplanation.class, failure);
      assertTrue(explanation.getDescription().endsWith("every later start has to use the same level."),
          explanation.getDescription());
      assertTrue(explanation.getAction().startsWith("Set 'vanillabp.adapters.c7.history-level: full'"),
          explanation.getAction());
      // the engine's own exception stays the cause, so nothing of it is lost
      assertTrue(failure.getCause().getMessage().startsWith("historyLevel mismatch"), failure.getCause().getMessage());
    } finally {
      first.close();
    }

  }

  @Test
  @DisplayName("'auto' takes the level a database was set up with")
  public void autoTakesTheStoredLevel() {

    final var first = build("c7", "history-auto", new Camunda7EngineProperties());
    try {
      final var second = build("c7", "history-auto", properties("auto"));
      try {
        assertEquals(HistoryLevel.HISTORY_LEVEL_FULL, configurationOf(second).getHistoryLevel());
      } finally {
        second.close();
      }
    } finally {
      first.close();
    }

  }

  @Test
  @DisplayName("a level the engine does not know ends the start with the list of the levels it knows")
  public void anUnknownLevelIsExplained() {

    final var failure = assertThrows(IllegalStateException.class,
        () -> build("c7", "history-unknown", properties("everything")));

    final var message = failure.getMessage();
    assertTrue(message.contains("'vanillabp.adapters.c7.history-level' is 'everything'"), message);
    assertTrue(message.contains(NAMES_THE_LEVELS
        + "."), message);

  }

  @Test
  @DisplayName("a level an engine plugin adds is allowed and named in the list")
  public void aCustomLevelIsAllowedAndListed() {

    final var custom = new PerTaskLevel();

    final var engine = build("c7", "history-custom", properties("per-task"), custom);
    try {
      assertSame(custom, configurationOf(engine).getHistoryLevel());
    } finally {
      engine.close();
    }

    final var failure = assertThrows(IllegalStateException.class,
        () -> build("c7", "history-custom-unknown", properties("per-tusk"), new PerTaskLevel()));
    assertTrue(failure.getMessage().contains(NAMES_THE_LEVELS
        + ", per-task."), failure.getMessage());

  }

  @Test
  @DisplayName("an empty value and the old name 'variable' end the start before the engine is built")
  public void anEmptyValueAndTheOldNameAreRefused() {

    for (final var configured : List.of("", " ", "variable")) {
      final var failure = assertThrows(IllegalStateException.class,
          () -> Camunda7HistoryLevel.applyTo("c7", new StandaloneInMemProcessEngineConfiguration(),
              properties(configured)));
      assertTrue(failure.getMessage().contains(NAMES_THE_LEVELS), failure.getMessage());
    }

  }

  @Test
  @DisplayName("a failure which has nothing to do with the history level is left as it is")
  public void otherFailuresAreLeftAlone() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    final var properties = new Camunda7EngineProperties();

    // before the engine knew its levels
    assertDoesNotThrow(() -> Camunda7HistoryLevel.explainWhyTheEngineDidNotStart("c7", configuration, properties,
        new ProcessEngineException("the database is gone")));

    // after it knew them, and with a level it knows
    configuration.initHistoryLevel();
    assertDoesNotThrow(() -> Camunda7HistoryLevel.explainWhyTheEngineDidNotStart("c7", configuration, properties,
        new ProcessEngineException("the database is gone")));

  }

  private static Camunda7EngineProperties properties(
      final String historyLevel) {

    final var properties = new Camunda7EngineProperties();
    properties.setHistoryLevel(historyLevel);
    return properties;

  }

  /**
   * Builds an engine the way both platforms do: the key first, then whatever else, and the
   * explanation when building fails.
   */
  private static ProcessEngine build(
      final String adapterId,
      final String database,
      final Camunda7EngineProperties properties,
      final HistoryLevel... customLevels) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database));
    configuration.setProcessEngineName("%s-%s".formatted(database, System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    // the second engine on a database finds the tables of the first one, as in an upgrade
    configuration.setDatabaseSchemaUpdate("true");
    if (customLevels.length > 0) {
      configuration.setCustomHistoryLevels(List.of(customLevels));
    }
    Camunda7HistoryLevel.applyTo(adapterId, configuration, properties);
    try {
      return configuration.buildProcessEngine();
    } catch (final RuntimeException e) {
      Camunda7HistoryLevel.explainWhyTheEngineDidNotStart(adapterId, configuration, properties, e);
      throw e;
    }

  }

  private static ProcessEngineConfigurationImpl configurationOf(
      final ProcessEngine engine) {

    return (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

  }

  private static String storedLevel(
      final ProcessEngine engine) {

    return engine
        .getManagementService()
        .getProperties()
        .get("historyLevel");

  }

  /** A level of its own, the way an engine plugin adds one. */
  private static final class PerTaskLevel implements HistoryLevel {

    @Override
    public int getId() {
      return 42;
    }

    @Override
    public String getName() {
      return "per-task";
    }

    @Override
    public boolean isHistoryEventProduced(
        final HistoryEventType eventType,
        final Object entity) {
      return false;
    }

  }

}
