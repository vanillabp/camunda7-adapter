package io.vanillabp.camunda7.quarkus.runtime;

import java.util.List;
import java.util.Map;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the Camunda 7 adapter's per-adapter-id beans on Quarkus - ONE
 * {@link Camunda7ProcessService} and ONE {@link Camunda7DeploymentService} per
 * configured adapter id of type {@code camunda7}, each wired to ITS engine from the
 * {@link Camunda7QuarkusEngineRegistry} (the per-adapter-id shape: a CDI producer
 * cannot yield N element beans for N runtime-configured ids, so ONE bean of type
 * <code>List&lt;...&gt;</code> is produced per SPI).
 * <p>
 * Platform contract: the List's element type is the SPI interface with the type
 * parameters literally {@code Object} - CDI's parameterized-type matching of
 * differing type arguments is not reliable across modes, so the platform looks the
 * beans up with the exact type. The producer methods are {@code @Singleton}
 * (deployment services are not client-proxyable).
 */
@ApplicationScoped
public class Camunda7AdapterProducer {

  @Produces
  @Singleton
  public List<MigratableProcessService<Object>> camunda7MigratableProcessServices(
      final MigrationAdapterProperties properties,
      final Camunda7QuarkusEngineRegistry engineRegistry) {

    return camunda7AdapterIds(properties)
        .stream()
        .<MigratableProcessService<Object>>map(adapterId -> {
          final var engine = engineRegistry.engineFor(adapterId);
          return new Camunda7ProcessService<>(
              adapterId, engine.getRuntimeService(), engine.getTaskService(), engine.usesSeparateDataSource());
        })
        .toList();

  }

  @Produces
  @Singleton
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  public List<AdapterDeploymentService<Object, Object>> camunda7AdapterDeploymentServices(
      final MigrationAdapterProperties properties,
      final Camunda7QuarkusEngineRegistry engineRegistry,
      final WorkflowTaskRegistry workflowTaskRegistry) {

    return (List) camunda7AdapterIds(properties)
        .stream()
        .map(adapterId -> {
          final var engine = engineRegistry.engineFor(adapterId);
          return new Camunda7DeploymentService(
              adapterId, engine.getRepositoryService(), engine, workflowTaskRegistry, engine.getTaskRegistry());
        })
        .toList();

  }

  private static List<String> camunda7AdapterIds(
      final MigrationAdapterProperties properties) {

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7Adapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

  }

}
