package io.vanillabp.camunda7.springboot.processservice;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
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
 * as an eager dependency.
 */
@AutoConfiguration(after = SpringBootMigrationAdapterAutoConfiguration.class)
public class Camunda7ProcessServiceConfiguration {

  @Bean
  public Camunda7ProcessService<?> camunda7MigratableProcessService(
      final ObjectProvider<MigrationAdapterProperties> properties) {

    final var adapterId = properties
        .getObject()
        .getAdapters()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7AdapterConfiguration.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

    return new Camunda7ProcessService<>(adapterId);

  }

}
