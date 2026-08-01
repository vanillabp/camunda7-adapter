package io.vanillabp.camunda7.springboot.it;

import org.camunda.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Boot smoke test proving that the Camunda 7 adapter is discovered purely by
 * configuration ({@code vanillabp.adapters.c7.type: camunda7}). The Camunda 7 beans
 * resolve the embedded engine's DataSource lazily, so the test wires a plain-JDBC H2
 * setup ({@code DataSourceAutoConfiguration} +
 * {@code DataSourceTransactionManagerAutoConfiguration}, deliberately NO JPA). There
 * are no BPMN files and no {@code @WorkflowService} classes, so no workflow is
 * deployed.
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
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");

          // the embedded engine was wired (plain JDBC, no JPA), named after the
          // adapter id, with the job executor still INACTIVE (deferred activation:
          // startWorkflowProcessing starts it - story 26e)
          final var engine = context.getBean(ProcessEngine.class);
          Assertions.assertEquals("vanillabp-camunda7-c7", engine.getName());
          final var holder = context.getBean(Camunda7EngineHolder.class);
          Assertions.assertFalse(
              holder.isJobExecutorActive(),
              "the job executor must not be active before startWorkflowProcessing");
          Assertions.assertFalse(holder.usesSeparateDataSource());

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
  public void twoAdapterIdsOfTypeCamunda7YieldPerIdEnginesAndBeans() {

    // per-adapter-id convention (stories 26d/26e): TWO ids of type camunda7 yield
    // one ENGINE, one process service and one deployment service PER id - the
    // second id needs its own datasource (two embedded engines must never share
    // one schema, see the validation test below)
    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-two-ids-test;DB_CLOSE_DELAY=-1",
            "vanillabp.prioritized-adapters=c7,c7-two",
            "vanillabp.adapters.c7-two.type=camunda7",
            "vanillabp.adapters.c7-two.data-source.url=jdbc:h2:mem:camunda7-two-ids-own;DB_CLOSE_DELAY=-1",
            "vanillabp.workflow-modules.c7-smoke-test.adapters.c7-two.resources-location=classpath*:c7-smoke-test/processes-two")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");

          // one engine per adapter id, named after the id
          final var engineNames = context
              .getBeansOfType(ProcessEngine.class)
              .values()
              .stream()
              .map(ProcessEngine::getName)
              .collect(java.util.stream.Collectors.toSet());
          Assertions.assertEquals(
              java.util.Set.of("vanillabp-camunda7-c7", "vanillabp-camunda7-c7-two"),
              engineNames);

          // the id with its own datasource is marked accordingly (its starts use
          // the two-phase pattern - see Camunda7ProcessService)
          final var holders = context.getBeansOfType(Camunda7EngineHolder.class);
          Assertions.assertFalse(holders.get("Camunda7_Engine_c7").usesSeparateDataSource());
          Assertions.assertTrue(holders.get("Camunda7_Engine_c7-two").usesSeparateDataSource());

          final var deploymentServiceIds = context
              .getBeanProvider(AdapterDeploymentService.class)
              .stream()
              .map(service -> ((AdapterDeploymentService<?, ?>) service).getAdapterId())
              .collect(java.util.stream.Collectors.toSet());
          Assertions.assertEquals(java.util.Set.of("c7", "c7-two"), deploymentServiceIds);

          final var processServices = context
              .getBeanProvider(io.vanillabp.integration.adapter.spi.MigratableProcessService.class)
              .stream()
              .map(service -> (io.vanillabp.integration.adapter.spi.MigratableProcessService<?>) service)
              .collect(java.util.stream.Collectors
                  .toMap(io.vanillabp.integration.adapter.spi.MigratableProcessService::getAdapterId,
                      service -> service));
          Assertions.assertEquals(java.util.Set.of("c7", "c7-two"), processServices.keySet());
          // shared datasource joins the caller's transaction (no two-phase commit),
          // an own datasource cannot - such ids use the two-phase pattern
          Assertions.assertFalse(processServices.get("c7").needsTwoPhaseCommitForStartingWorkflows());
          Assertions.assertTrue(processServices.get("c7-two").needsTwoPhaseCommitForStartingWorkflows());

        });

  }

  @Test
  public void twoAdapterIdsSharingTheSameDataSourceFailWithGuidingMessage() {

    // two embedded engines on one schema are the same engine state - configuring
    // them as two adapters must fail at startup with a guiding message (26c style)
    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-same-ds-test;DB_CLOSE_DELAY=-1",
            "vanillabp.prioritized-adapters=c7,c7-two",
            "vanillabp.adapters.c7-two.type=camunda7",
            "vanillabp.workflow-modules.c7-smoke-test.adapters.c7-two.resources-location=classpath*:c7-smoke-test/processes-two")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "boot has to fail with a guiding message");

          var cause = (Throwable) context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = String.valueOf(cause.getMessage());
          Assertions.assertTrue(
              message.contains("'c7', 'c7-two'"),
              "expected the guiding message naming both adapter ids but got: "
                  + message);
          Assertions.assertTrue(message.contains("share the same datasource"));
          Assertions.assertTrue(message.contains("vanillabp.adapters.<id>.data-source.url"));

        });

  }

  @Test
  public void configuredAdapterWithoutDataSourceFailsWithGuidingMessage() {

    // a configured camunda7 adapter WITHOUT any DataSource: the boot fails with a
    // guiding message naming the remedy - not with a NoSuchBeanDefinitionException
    // (story 26c: configuration defects surface at startup)
    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "boot has to fail with a guiding message");

          var cause = (Throwable) context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = String.valueOf(cause.getMessage());
          Assertions.assertTrue(
              message.contains("Camunda 7 adapter 'c7' is configured"),
              "expected the guiding message but got: "
                  + message);
          Assertions.assertTrue(message.contains("no DataSource/PlatformTransactionManager is available"));
          Assertions.assertTrue(message.contains("spring.datasource."));

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
