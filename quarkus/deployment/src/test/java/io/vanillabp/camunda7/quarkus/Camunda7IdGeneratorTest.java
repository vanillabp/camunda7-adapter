package io.vanillabp.camunda7.quarkus;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.db.DbIdGenerator;
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
 * The key <code>vanillabp.adapters.&lt;id&gt;.id-generator: db</code> on Quarkus. The engine
 * makes its ids with its own DbIdGenerator, so the deployment of the workflow module gets a
 * number. What the two values do to a real engine is {@code Camunda7IdGeneratorTest} in the
 * core.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7IdGeneratorTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("id-generator-db/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Test
  public void dbGivesTheEngineItsDbIdGenerator() {

    final var engine = engineRegistry
        .engineFor("c7")
        .getProcessEngine();
    Assertions.assertInstanceOf(DbIdGenerator.class, ((ProcessEngineConfigurationImpl) engine
        .getProcessEngineConfiguration()).getIdGenerator());
    final var deployments = engine
        .getRepositoryService()
        .createDeploymentQuery()
        .list();
    Assertions.assertFalse(deployments.isEmpty());
    deployments
        .forEach(deployment -> Assertions.assertTrue(deployment.getId().matches("[0-9]+"), deployment::getId));

  }

}
