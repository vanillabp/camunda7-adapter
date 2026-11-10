package io.vanillabp.camunda7.springboot;

import java.util.List;

import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.spring.boot.starter.ClientProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * A key of Camunda's Spring Boot starter ends the start, except where something really reads
 * it. Camunda's starter of the external task client reads <code>camunda.bpm.client.*</code>.
 * An application may use it next to the adapter, to work on the external tasks of another
 * engine. With the starter on the classpath these keys stay. This module has the starter as a
 * test dependency, so the adapter finds it here by the name of {@link ClientProperties}. This
 * test goes red if that class is renamed, because then the keys below end the start.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ExternalTaskClientVersionOneKeysTest {

  private static final String WORKER_ID = "vanillabp-next-to-the-adapter";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(
          AutoConfigurations.of(
              org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
              org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
              org.camunda.bpm.client.spring.boot.starter.impl.ClientAutoConfiguration.class,
              Camunda7AdapterConfiguration.class,
              Camunda7ProcessServiceConfiguration.class,
              WorkflowModuleAutoConfiguration.class,
              SpringBootMigrationAdapterAutoConfiguration.class));

  @Test
  @DisplayName("The adapter finds Camunda's starter of the external task client by its class name")
  public void theStarterIsFoundByItsClassName() {

    Assertions.assertTrue(Camunda7VersionOneKeys.externalTaskClientIsPresent(getClass().getClassLoader()));

  }

  @Test
  @DisplayName("A key the external task client reads does not end the start, and it takes effect")
  public void aKeyTheExternalTaskClientReadsIsKept() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-v1-client-keys;DB_CLOSE_DELAY=-1",
            // no engine answers there, and the client must not ask it
            "camunda.bpm.client.base-url=http://localhost:1/engine-rest",
            "camunda.bpm.client.disable-auto-fetching=true",
            "camunda.bpm.client.worker-id="
                + WORKER_ID)
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          Assertions.assertNotNull(context.getBean(ExternalTaskClient.class));
          Assertions.assertEquals(WORKER_ID, context.getBean(ClientProperties.class).getWorkerId());

        });

  }

  @Test
  @DisplayName("With the starter, its keys stay in every spelling, and other keys of version 1 still end the start")
  public void withTheStarterOnlyItsKeysStay() {

    final var message = messageFor(
        true, "camunda.bpm.client.base-url", "camunda.bpm.client.basic-auth.username", "CAMUNDA_BPM_CLIENT_WORKER_ID",
        "camunda.bpm.history-level");
    Assertions.assertNotNull(message);
    Assertions.assertFalse(message.contains("client"), message);
    Assertions.assertTrue(message.contains("'camunda.bpm.history-level'"), message);

  }

  @Test
  @DisplayName("Without the starter, a key of the external task client ends the start and names the starter")
  public void withoutTheStarterItsKeysEndTheStart() {

    final var message = messageFor(false, "camunda.bpm.client.base-url");
    Assertions.assertNotNull(message);
    Assertions.assertTrue(
        message.contains("  - 'camunda.bpm.client.base-url': Add Camunda's starter "
            + "camunda-bpm-spring-boot-starter-external-task-client, which reads these keys, or remove the key."),
        message);

  }

  private static String messageFor(
      final boolean externalTaskClientIsPresent,
      final String... propertyNames) {

    try {
      Camunda7VersionOneKeys.endTheStartOnKeysOfVersionOne(List.of("c7"), List.of(propertyNames), false,
          externalTaskClientIsPresent);
      return null;
    } catch (final IllegalStateException e) {
      return e.getMessage();
    }

  }

}
