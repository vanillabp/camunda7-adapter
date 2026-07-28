package io.vanillabp.camunda7.springboot.it;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.deployment.Camunda7DeploymentConfiguration;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Boot smoke test proving that the Camunda 7 adapter is discovered purely by
 * configuration ({@code vanillabp.adapters.c7.type: camunda7}). There are no BPMN files
 * and no {@code @WorkflowService} classes, so no workflow is deployed.
 * <p>
 * The deployment lifecycle ({@code DeploymentAutoConfiguration}) is deliberately NOT
 * loaded: the core deployment pipeline invokes {@code deployResources} once per
 * (workflow module x prioritized adapter) even when a module has zero BPMN files, which
 * would hit the throwing skeleton stub (see the root README "Known issues"). This test
 * therefore proves discovery/registration only, mirroring the platform's own
 * {@code AdapterConfigurationTest}.
 */
public class Camunda7AdapterBootTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  public void adapterIsDiscoveredByConfiguration() {

    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                Camunda7AdapterConfiguration.class,
                Camunda7DeploymentConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          // the context started without touching the throwing deployment stubs
          Assertions.assertNull(context.getStartupFailure(), "context should start");

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

}
