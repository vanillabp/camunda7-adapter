package io.vanillabp.camunda7.springboot.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit test of the {@link Camunda7EngineHolder}'s job-executor lifecycle (story 26e):
 * the engine is built with the executor INACTIVE (deferred activation); the executor
 * is engine-global while start/stop is notified per workflow module, so the started
 * modules are reference-counted - the executor stops only when the LAST started
 * module stops, and unconditionally on {@code close()} (which is idempotent). The
 * holder is built standalone on the adapter's OWN datasource (H2), which also covers
 * the own-pool construction path.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7EngineHolderTest {

  private static Camunda7EngineProperties ownDataSourceProperties(
      final String database) {

    final var properties = new Camunda7EngineProperties();
    properties.getDataSource().setUrl("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database));
    return properties;

  }

  @Test
  @DisplayName("The job executor starts with the first module, stops with the last and on close")
  public void jobExecutorLifecycleIsReferenceCounted() {

    final var holder = new Camunda7EngineHolder(
        "holder-test", ownDataSourceProperties("holder-test"), null, null);
    // Spring lifecycle done manually: SpringProcessEngineConfiguration needs an
    // ApplicationContext (Spring-bean resolution in scripting/expressions)
    try (var applicationContext = new org.springframework.context.support.StaticApplicationContext()) {
      holder.setApplicationContext(applicationContext);
      holder.afterPropertiesSet();
    }
    try {

      Assertions.assertTrue(holder.usesSeparateDataSource());
      Assertions.assertEquals("vanillabp-camunda7-holder-test", holder.getProcessEngine().getName());

      // deferred activation: building the engine must not start the executor
      Assertions.assertFalse(holder.isJobExecutorActive(), "executor must be inactive after engine build");

      // first module starts the engine-global executor
      holder.startWorkflowProcessing("module-a");
      Assertions.assertTrue(holder.isJobExecutorActive());

      // a second module keeps it running...
      holder.startWorkflowProcessing("module-b");
      holder.stopWorkflowProcessing("module-a");
      Assertions.assertTrue(
          holder.isJobExecutorActive(),
          "stopping the FIRST module must not starve the remaining modules");

      // ...and only the last module's stop shuts it down
      holder.stopWorkflowProcessing("module-b");
      Assertions.assertFalse(holder.isJobExecutorActive());

      // restartable (e.g. tests or future lifecycle features)
      holder.startWorkflowProcessing("module-a");
      Assertions.assertTrue(holder.isJobExecutorActive());

    } finally {
      holder.close();
    }

    // close stops the executor unconditionally, before the engine closes - and is
    // safe to call more than once
    Assertions.assertFalse(holder.isJobExecutorActive());
    holder.close();

  }

}
