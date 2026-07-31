package io.vanillabp.camunda7.springboot;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;

/**
 * Registers the Camunda 7 adapter's per-adapter-id beans: for EACH configured adapter
 * id of type {@code camunda7} one {@link Camunda7ProcessService} <i>element</i> bean
 * and one {@link Camunda7DeploymentService} <i>element</i> bean are registered - never
 * beans of type {@code List<...>}: the platform collects element beans via
 * {@code ObjectProvider.stream()}.
 * <p>
 * The id set comes from the runtime configuration, so the beans are registered
 * programmatically ({@link BeanRegistrar} +
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}); the adapter id is a
 * CONSTRUCTOR parameter of each instance. Without any configured {@code camunda7}
 * adapter id no bean is registered at all - the config-based gating of the former
 * {@code @Conditional} wiring is preserved implicitly.
 * <p>
 * <b>Interim (until the per-id-engines story 26e):</b> all instances share the SINGLE
 * embedded engine's {@link RuntimeService}/{@link RepositoryService} - one engine per
 * configured adapter id (incl. per-id datasource/schema) is introduced there.
 */
public class Camunda7AdapterBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        Camunda7AdapterConfiguration.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "Camunda7_ProcessService_%s".formatted(adapterId),
              Camunda7ProcessService.class,
              spec -> spec.supplier(supplierContext -> new Camunda7ProcessService<>(
                  adapterId, engineService(supplierContext, RuntimeService.class, adapterId))));

          registry.registerBean(
              "Camunda7_DeploymentService_%s".formatted(adapterId),
              Camunda7DeploymentService.class,
              spec -> spec.supplier(supplierContext -> new Camunda7DeploymentService(
                  adapterId, engineService(supplierContext, RepositoryService.class, adapterId))));

        });

  }

  /**
   * Resolves an engine service with a GUIDING failure instead of a bean-wiring
   * error: the embedded engine is only created if a {@code DataSource} and a
   * {@code PlatformTransactionManager} are available - a configured adapter without
   * a database is a configuration defect the developer has to learn about with the
   * remedy named, not via a {@code NoSuchBeanDefinitionException}.
   *
   * @param <S> The engine-service type
   * @param supplierContext The bean supplier context
   * @param serviceType The engine-service type
   * @param adapterId The adapter ID (used in the guiding message)
   * @return The engine service
   */
  private static <S> S engineService(
      final BeanRegistry.SupplierContext supplierContext,
      final Class<S> serviceType,
      final String adapterId) {

    final var service = supplierContext
        .beanProvider(serviceType)
        .getIfAvailable();
    if (service == null) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' is configured ('vanillabp.adapters.%s.type: camunda7') but the \
              embedded engine was not created: no DataSource/PlatformTransactionManager is available. \
              Camunda 7 runs embedded and always needs a database - configure 'spring.datasource.*' \
              (and a transaction manager, e.g. via the JDBC or JPA starters) or remove the adapter \
              configuration."""
              .formatted(adapterId, adapterId));
    }
    return service;

  }

}
