package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Quarkus' own name of the default datasource, <code>&lt;default&gt;</code>, in
 * <code>data-source-name</code> is no datasource of its own. It is the same datasource
 * the reserved value <code>default</code> names. So the engine does not deduplicate
 * deliveries, and the task registry knows it shares the application's datasource.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7DefaultDataSourceByItsNameTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("default-by-name/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Test
  public void quarkusNameOfTheDefaultDataSourceIsNoDataSourceOfItsOwn() {

    final var engine = engineRegistry.engineFor("c7");
    Assertions.assertFalse(engine.usesSeparateDataSource());
    Assertions.assertFalse(engine
        .getTaskRegistry()
        .engineRunsOnItsOwnDataSource());

  }

}
