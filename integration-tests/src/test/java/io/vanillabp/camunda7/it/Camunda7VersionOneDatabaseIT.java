package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.HistoryLevel;
import org.camunda.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A database which version 1 of VanillaBP set up, started with version 2. Version 1 had its
 * engine built by Camunda's Spring Boot starter, which uses the history level <code>full</code>
 * and UUIDs as ids. The engine stores the level in the database, and an engine of version 2
 * with the engine's own default <code>audit</code> did not start on such a database.
 * <p>
 * The test sets up the database with a {@link VersionOneEngine}, configured like the starter
 * of version 1: the level <code>full</code>, the {@link StrongUuidGenerator} and a running
 * workflow. On 2026-11-09 the same case was measured with the application of version 1
 * itself, on PostgreSQL.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7VersionOneDatabaseIT {

  private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** The process which the engine of version 1 deployed and started. No VanillaBP class claims it. */
  private static final String VERSION_ONE_PROCESS = "VersionOneProcess";

  @Test
  @DisplayName("a database of version 1 starts without a key, keeps its workflows, and new ids are UUIDs again")
  public void aDatabaseOfVersionOneStarts() {

    final var database = "c7-version-one-database";
    final var runningWorkflow = setUpLikeVersionOne(database);

    try (var context = new SpringApplicationBuilder(TestApplication.class).run(urlOf(database))) {
      final var engine = context.getBean(ProcessEngine.class);
      final var configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

      assertEquals(HistoryLevel.HISTORY_LEVEL_FULL, configuration.getHistoryLevel());
      assertInstanceOf(StrongUuidGenerator.class, configuration.getIdGenerator());
      assertEquals(1, engine
          .getRuntimeService()
          .createProcessInstanceQuery()
          .processInstanceId(runningWorkflow)
          .count(), "the workflow version 1 started is still there");

      // what the adapter deployed while the application started, and a workflow started now
      final var deployedByVersionTwo = engine
          .getRepositoryService()
          .createDeploymentQuery()
          .tenantIdIn("c7-it")
          .list();
      assertFalse(deployedByVersionTwo.isEmpty());
      final var newIds = new ArrayList<>(deployedByVersionTwo
          .stream()
          .map(Deployment::getId)
          .toList());
      newIds.add(engine
          .getRuntimeService()
          .startProcessInstanceByKey(VERSION_ONE_PROCESS)
          .getId());
      newIds.forEach(id -> assertTrue(UUID.matcher(id).matches(), "not a UUID: "
          + id));
    }

  }

  @Test
  @DisplayName("a new database gets the level 'full', as in version 1")
  public void aNewDatabaseGetsFull() {

    try (var context = new SpringApplicationBuilder(TestApplication.class).run(urlOf("c7-version-one-new-database"))) {
      final var engine = context.getBean(ProcessEngine.class);

      assertEquals("3", engine
          .getManagementService()
          .getProperties()
          .get("historyLevel"));
    }

  }

  @Test
  @DisplayName("another level on a database of version 1 ends the start with the key and both levels named")
  public void anotherLevelIsExplained(
      final CapturedOutput output) {

    final var database = "c7-version-one-database-audit";
    setUpLikeVersionOne(database);

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(urlOf(database), "--vanillabp.adapters.c7.history-level=audit")
            .close());

    final var message = messageOfTheAdapter(failure);
    assertTrue(message.contains("The database stores 'full', but 'vanillabp.adapters.c7.history-level' is 'audit'"),
        message);
    assertTrue(message.contains("Set 'vanillabp.adapters.c7.history-level: full'"), message);
    assertTheReportStartsWithTheAdapter(output, "Set 'vanillabp.adapters.c7.history-level: full'");

  }

  @Test
  @DisplayName("a level the engine does not know ends the start with the list of the allowed ones")
  public void anUnknownLevelIsExplained(
      final CapturedOutput output) {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(urlOf("c7-version-one-unknown-level"), "--vanillabp.adapters.c7.history-level=everything")
            .close());

    final var message = messageOfTheAdapter(failure);
    assertTrue(message.contains("'vanillabp.adapters.c7.history-level' is 'everything'"), message);
    assertTrue(message.contains("Use one of: none, activity, audit, full, auto."), message);
    assertTheReportStartsWithTheAdapter(output, "Use one of: none, activity, audit, full, auto.");

  }

  /**
   * Spring Boot's report of the failed start shows the adapter's explanation first, under
   * "Description" and "Action", and not the chain of bean creation exceptions around it.
   */
  private static void assertTheReportStartsWithTheAdapter(
      final CapturedOutput output,
      final String action) {

    final var printed = output.getAllOfThisTest();
    final var report = printed.indexOf("APPLICATION FAILED TO START");
    assertTrue(report >= 0, printed);
    final var afterTheHeadline = printed.substring(report);
    final var description = afterTheHeadline.indexOf("Description:");
    final var actionHeadline = afterTheHeadline.indexOf("Action:");
    assertTrue((description >= 0) && (actionHeadline > description), afterTheHeadline);
    assertTrue(
        afterTheHeadline
            .substring(description + "Description:".length())
            .strip()
            .startsWith("Camunda 7 adapter 'c7' could not start its engine"),
        afterTheHeadline);
    assertTrue(
        afterTheHeadline
            .substring(actionHeadline + "Action:".length())
            .strip()
            .startsWith(action),
        afterTheHeadline);

  }

  private static String urlOf(
      final String database) {

    return "--spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database);

  }

  /**
   * Sets up the tables the way an application of version 1 left them, with a workflow which
   * still runs.
   *
   * @return The id of that workflow
   */
  private static String setUpLikeVersionOne(
      final String database) {

    final var engine = VersionOneEngine.on(database, "version-one-%s".formatted(database));
    try {
      engine
          .getRepositoryService()
          .createDeployment()
          .addModelInstance(
              "version-one.bpmn",
              Bpmn
                  .createExecutableProcess(VERSION_ONE_PROCESS)
                  .camundaHistoryTimeToLive(180)
                  .startEvent()
                  .userTask("waitHere")
                  .endEvent()
                  .done())
          .deploy();
      return engine
          .getRuntimeService()
          .startProcessInstanceByKey(VERSION_ONE_PROCESS)
          .getId();
    } finally {
      engine.close();
    }

  }

  /** The message of the adapter's exception in the chain of causes, which has to be there. */
  private static String messageOfTheAdapter(
      final Throwable failure) {

    final List<String> messages = new ArrayList<>();
    for (var current = failure; current != null; current = current.getCause()) {
      if ((current instanceof IllegalStateException) && (current.getMessage() != null) && current
          .getMessage()
          .startsWith("Camunda 7 adapter 'c7' could not start its engine")) {
        return current.getMessage();
      }
      messages.add(String.valueOf(current.getMessage()));
      if (current.getCause() == current) {
        break;
      }
    }
    throw new AssertionError("no message of the adapter in: "
        + messages);

  }

}
