package io.vanillabp.camunda7.springboot.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * <code>allow-listeners</code> is not read at task level. A value set there ends the start, and
 * the message names the key and the levels where it works.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SwitchAtTaskLevelEndsTheStartTest {

  private static final String TASK_LEVEL_KEY = "vanillabp.workflow-modules.c7-smoke-test.workflows.SomeProcess.tasks.someTask.adapters.c7.allow-listeners";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(
          AutoConfigurations.of(
              org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
              org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
              Camunda7AdapterConfiguration.class,
              Camunda7ProcessServiceConfiguration.class,
              WorkflowModuleAutoConfiguration.class,
              SpringBootMigrationAdapterAutoConfiguration.class));

  @Test
  @DisplayName("allow-listeners at task level ends the start, and the message says where the key works")
  public void allowListenersAtTaskLevelEndsTheStart() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:c7-switch-at-task-level;DB_CLOSE_DELAY=-1",
            TASK_LEVEL_KEY
                + "=true")
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "the start has to end");
          final var causes = new StringBuilder();
          for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
            causes
                .append(cause.getMessage())
                .append('\n');
          }
          final var message = causes.toString();
          Assertions.assertTrue(
              message
                  .contains("Camunda 7 adapter 'c7' has 'allow-listeners' set at TASK level, where nothing reads it"),
              () -> "expected the guiding startup failure but got:\n"
                  + message);
          Assertions.assertTrue(message.contains(TASK_LEVEL_KEY), message);
          Assertions.assertTrue(
              message.contains("vanillabp.workflow-modules.<m>.workflows.<w>.adapters.c7.allow-listeners"),
              message);
          Assertions.assertTrue(message.contains("Move the value to one of these levels, or remove it."), message);

        });

  }

}
