package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.db.DbIdGenerator;
import org.camunda.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the key <code>id-generator</code> does to a real engine on H2. The default makes
 * UUIDs, as version 1 did. With <code>db</code> a deployment and a started workflow get
 * numbers, which the engine takes in blocks from the database. Any other value ends the
 * start with the two allowed values.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7IdGeneratorTest {

  private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Test
  @DisplayName("the default is 'uuid', and a deployment and a workflow get UUIDs")
  public void theDefaultMakesUuids() {

    assertEquals("uuid", new Camunda7EngineProperties().getIdGenerator());

    final var engine = build("ids-default", new Camunda7EngineProperties());
    try {
      assertInstanceOf(StrongUuidGenerator.class, configurationOf(engine).getIdGenerator());
      for (final var id : deployAndStart(engine)) {
        assertTrue(id.matches(UUID_PATTERN), "not a UUID: "
            + id);
      }
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("with 'db' a deployment and a workflow get numbers from the engine's DbIdGenerator")
  public void dbMakesNumbers() {

    final var engine = build("ids-db", properties(" DB "));
    try {
      assertInstanceOf(DbIdGenerator.class, configurationOf(engine).getIdGenerator());
      for (final var id : deployAndStart(engine)) {
        assertTrue(id.matches("[0-9]+"), "not a number: "
            + id);
      }
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("any other value ends the start before the engine is built and names the allowed values")
  public void anUnknownValueIsExplained() {

    for (final var configured : List.of("", "strong", "simple")) {
      final var failure = assertThrows(Camunda7StartupExplanation.class,
          () -> Camunda7IdGenerator.applyTo("c7", new StandaloneInMemProcessEngineConfiguration(),
              properties(configured)));
      final var message = failure.getMessage();
      assertTrue(message.contains("'vanillabp.adapters.c7.id-generator' is '%s'".formatted(configured)), message);
      assertTrue(failure.getAction().startsWith("Use 'uuid' or 'db'."), failure.getAction());
    }

  }

  private static Camunda7EngineProperties properties(
      final String idGenerator) {

    final var properties = new Camunda7EngineProperties();
    properties.setIdGenerator(idGenerator);
    return properties;

  }

  /** Builds an engine the way both platforms do, with the key applied before the build. */
  private static ProcessEngine build(
      final String database,
      final Camunda7EngineProperties properties) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database));
    configuration.setProcessEngineName("%s-%s".formatted(database, System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setDbMetricsReporterActivate(false);
    configuration.setHistoryTimeToLive("P1D");
    Camunda7IdGenerator.applyTo("c7", configuration, properties);
    return configuration.buildProcessEngine();

  }

  /**
   * Deploys a process with a user task and starts it.
   *
   * @return The ids of the deployment, the process instance and the task
   */
  private static List<String> deployAndStart(
      final ProcessEngine engine) {

    final var model = Bpmn
        .createExecutableProcess("IdProcess")
        .startEvent()
        .userTask("Wait")
        .endEvent()
        .done();
    final var deployment = engine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("id-process.bpmn", model)
        .deploy();
    final var instance = engine
        .getRuntimeService()
        .startProcessInstanceByKey("IdProcess");
    final var task = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(instance.getId())
        .singleResult();
    return List.of(deployment.getId(), instance.getId(), task.getId());

  }

  private static ProcessEngineConfigurationImpl configurationOf(
      final ProcessEngine engine) {

    return (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();

  }

}
