package io.vanillabp.camunda7.springboot.processservice;

import java.util.Map;

import org.camunda.bpm.engine.RuntimeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguredCondition;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Provides the Camunda 7 adapter's {@link Camunda7ProcessService} bean picked up by the
 * {@link io.vanillabp.spi.process.ProcessService} beans built by the VanillaBP Spring
 * Boot integration.
 * <p>
 * The adapter id is resolved from the configuration (first adapter of type
 * {@value Camunda7AdapterConfiguration#ADAPTER_TYPE}). An {@link ObjectProvider} is used
 * so the {@link MigrationAdapterProperties} bean is materialized on demand rather than
 * as an eager dependency. Like all Camunda 7 beans, gated on a configured
 * {@code camunda7} adapter - the engine (and with it the {@link RuntimeService}) then
 * exists by condition, so no null-engine tolerance is needed.
 */
@AutoConfiguration(after = {
    SpringBootMigrationAdapterAutoConfiguration.class, Camunda7EngineConfiguration.class
})
@Conditional(Camunda7AdapterConfiguredCondition.class)
@ConditionalOnBean(org.camunda.bpm.engine.ProcessEngine.class)
public class Camunda7ProcessServiceConfiguration {

  @Bean
  public Camunda7ProcessService<?> camunda7MigratableProcessService(
      final ObjectProvider<MigrationAdapterProperties> properties,
      final RuntimeService runtimeService) {

    final var adapterId = properties
        .getObject()
        .getAdapters()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7AdapterConfiguration.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

    return new Camunda7ProcessService<>(adapterId, runtimeService);

  }

}
