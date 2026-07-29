package io.vanillabp.camunda7.springboot.it;

import org.camunda.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.deployment.Camunda7DeploymentConfiguration;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineConfiguration;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Boot smoke test proving that the Camunda 7 adapter is discovered purely by
 * configuration ({@code vanillabp.adapters.c7.type: camunda7}). The Camunda 7 beans
 * condition on the embedded engine, so the test wires a plain-JDBC H2 setup
 * ({@code DataSourceAutoConfiguration} + {@code DataSourceTransactionManagerAutoConfiguration},
 * deliberately NO JPA) - proving at the same time that the auto-configuration
 * ordering works for plain-JDBC applications. There are no BPMN files and no
 * {@code @WorkflowService} classes, so no workflow is deployed.
 * <p>
 * The deployment lifecycle ({@code DeploymentAutoConfiguration}) is deliberately NOT
 * loaded: the core deployment pipeline would invoke {@code deployResources} once per
 * (workflow module x prioritized adapter); deploying real BPMN is covered by the
 * integration-tests module. This test proves discovery/registration only.
 */
public class Camunda7AdapterBootTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  public void adapterIsDiscoveredByConfiguration() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-boot-test;DB_CLOSE_DELAY=-1")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7EngineConfiguration.class,
                Camunda7DeploymentConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");

          // the embedded engine was wired (plain JDBC, no JPA)
          Assertions.assertNotNull(context.getBean(ProcessEngine.class));

          // element-bean convention: one AdapterDeploymentService bean per adapter
          // (never a List bean) so several adapter types can coexist
          final var deploymentService = context.getBean(AdapterDeploymentService.class);
          Assertions.assertEquals("c7", deploymentService.getAdapterId());
          Assertions.assertEquals(
              Camunda7DeploymentService.ADAPTER_TYPE,
              deploymentService.getAdapterType());
          Assertions.assertEquals("camunda7", deploymentService.getAdapterType());

        });

  }

  @Test
  public void withoutConfiguredCamunda7AdapterNoEngineIsCreated() {

    // the adapter jar is on the classpath and a data source exists, but NO
    // vanillabp.adapters.<id>.type=camunda7 is configured: the gate must prevent
    // the engine (and with it the ACT_* tables and the job executor) entirely
    this.contextRunner
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:camunda7-gate-test;DB_CLOSE_DELAY=-1")
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7EngineConfiguration.class,
                Camunda7DeploymentConfiguration.class,
                Camunda7ProcessServiceConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          Assertions.assertTrue(
              context.getBeansOfType(ProcessEngine.class).isEmpty(),
              "no engine may be created without a configured camunda7 adapter");
          Assertions.assertTrue(
              context.getBeansOfType(Camunda7DeploymentService.class).isEmpty());

          // and no ACT_* tables were created in the business database
          final var dataSource = context.getBean(javax.sql.DataSource.class);
          try (var connection = dataSource.getConnection(); var tables = connection.getMetaData().getTables(null, null,
              "ACT_%", null)) {
            Assertions.assertFalse(tables.next(), "no ACT_* tables may exist");
          }

        });

  }

}
