package io.vanillabp.camunda7.springboot.deployment;

import java.util.Map;

import org.camunda.bpm.engine.RepositoryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguredCondition;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Registers the {@link Camunda7DeploymentService} as an <i>element</i> bean - never
 * as a bean of type <code>List&lt;AdapterDeploymentService&gt;</code>: the platform
 * collects all adapters' deployment services via <code>ObjectProvider</code> streams,
 * and only element beans allow several adapter types to coexist in one application
 * (the central migration scenario; a List bean per adapter breaks collection
 * injection as soon as a second adapter is present).
 * <p>
 * Currently ONE instance is built for the first configured adapter id of type
 * {@value Camunda7AdapterConfiguration#ADAPTER_TYPE} - per-adapter-id multiplicity
 * (one element bean per configured id, e.g. two Camunda 7 engines side by side
 * during a migration) is introduced by the adapter-config-model story (26d).
 */
@AutoConfiguration(after = {
    SpringBootMigrationAdapterAutoConfiguration.class, Camunda7EngineConfiguration.class
})
@Conditional(Camunda7AdapterConfiguredCondition.class)
@ConditionalOnBean(org.camunda.bpm.engine.ProcessEngine.class)
public class Camunda7DeploymentConfiguration {

  @Bean
  public Camunda7DeploymentService camunda7DeploymentService(
      final MigrationAdapterProperties properties,
      final RepositoryService repositoryService) {

    final var adapterId = properties
        .getAdapters()
        .entrySet()
        .stream()
        .filter(adapter -> adapter.getValue().equals(Camunda7AdapterConfiguration.ADAPTER_TYPE))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

    return new Camunda7DeploymentService(adapterId, repositoryService);

  }

}
