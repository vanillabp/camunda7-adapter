package io.vanillabp.camunda7.springboot;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;

/**
 * Registers the Camunda 7 adapter's per-adapter-id beans: for EACH configured adapter
 * id of type {@code camunda7} (multiple ids of one BPMS type = the migration scenario,
 * e.g. two embedded engines side by side on separate schemas) one
 * {@link Camunda7EngineHolder} bean owning THAT id's embedded engine (engine name
 * <code>vanillabp-camunda7-&lt;id&gt;</code>, incl. job-executor and - optional - own
 * datasource lifecycle), one {@link Camunda7ProcessService} <i>element</i> bean, one
 * {@link Camunda7DeploymentService} <i>element</i> bean and named per-id
 * {@link ProcessEngine}/{@link RuntimeService}/{@link RepositoryService} convenience
 * beans - never beans of type {@code List<...>}: the platform collects element beans
 * via {@code ObjectProvider.stream()}.
 * <p>
 * The id set comes from the runtime configuration, so the beans are registered
 * programmatically ({@link BeanRegistrar} +
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}); the adapter id is a
 * CONSTRUCTOR parameter of each instance. Without any configured {@code camunda7}
 * adapter id no bean is registered at all - no {@code ACT_*} tables and no job
 * executor appear in applications merely having the adapter jar on the classpath.
 * <p>
 * <b>Startup validation:</b> two embedded engines on one schema are the same engine
 * state - more than one {@code camunda7} adapter id resolving to the SAME datasource
 * (both the application's, or the same <code>data-source-name</code>) fails the boot
 * with a guiding message naming the property keys to fix.
 */
public class Camunda7AdapterBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    final var camunda7AdapterIds = new LinkedList<String>();
    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        Camunda7AdapterConfiguration.ADAPTER_TYPE,
        camunda7AdapterIds::add);

    validateDistinctDataSources(environment, camunda7AdapterIds);

    camunda7AdapterIds
        .forEach(adapterId -> {

          registry.registerBean(
              "Camunda7_Engine_%s".formatted(adapterId),
              Camunda7EngineHolder.class,
              spec -> spec.supplier(supplierContext -> {
                final var engineProperties = enginePropertiesFor(environment, adapterId);
                // the application's DEFAULT DataSource/PlatformTransactionManager
                // are only needed (and only required to exist) if the id references
                // no named datasource bean (data-source-name)
                final var usesNamedDataSource = engineProperties.usesSeparateDataSource();
                return new Camunda7EngineHolder(
                    adapterId, engineProperties, usesNamedDataSource
                        ? null
                        : applicationBean(supplierContext, DataSource.class, adapterId), usesNamedDataSource
                            ? null
                            : applicationBean(supplierContext, PlatformTransactionManager.class,
                                adapterId), supplierContext
                                    .bean(WorkflowTaskInvoker.class));
              }));

          registry.registerBean(
              "Camunda7_ProcessService_%s".formatted(adapterId),
              Camunda7ProcessService.class,
              spec -> spec.supplier(supplierContext -> {
                final var engine = engineHolder(supplierContext, adapterId);
                return new Camunda7ProcessService<>(
                    adapterId, engine.getRuntimeService(), engine.usesSeparateDataSource());
              }));

          registry.registerBean(
              "Camunda7_DeploymentService_%s".formatted(adapterId),
              Camunda7DeploymentService.class,
              spec -> spec.supplier(supplierContext -> {
                final var engine = engineHolder(supplierContext, adapterId);
                return new Camunda7DeploymentService(
                    adapterId, engine.getRepositoryService(), engine, supplierContext
                        .bean(WorkflowTaskInvoker.class), engine.getTaskRegistry());
              }));

          // named convenience beans, e.g. for tests and applications integrating
          // with the engine directly (unique by type in single-adapter setups)
          registry.registerBean(
              "Camunda7_ProcessEngine_%s".formatted(adapterId),
              ProcessEngine.class,
              spec -> spec.supplier(supplierContext -> engineHolder(supplierContext, adapterId).getProcessEngine()));
          registry.registerBean(
              "Camunda7_RuntimeService_%s".formatted(adapterId),
              RuntimeService.class,
              spec -> spec.supplier(supplierContext -> engineHolder(supplierContext, adapterId).getRuntimeService()));
          registry.registerBean(
              "Camunda7_RepositoryService_%s".formatted(adapterId),
              RepositoryService.class,
              spec -> spec
                  .supplier(supplierContext -> engineHolder(supplierContext, adapterId).getRepositoryService()));

        });

  }

  private static Camunda7EngineHolder engineHolder(
      final BeanRegistry.SupplierContext supplierContext,
      final String adapterId) {

    return supplierContext.bean("Camunda7_Engine_%s".formatted(adapterId), Camunda7EngineHolder.class);

  }

  /**
   * Binds the adapter id's engine settings from the canonical per-adapter location
   * <code>vanillabp.adapters.&lt;id&gt;.*</code> (the overlay view, see
   * {@link VanillaBpCamunda7Properties}).
   */
  private static Camunda7EngineProperties enginePropertiesFor(
      final Environment environment,
      final String adapterId) {

    return Binder
        .get(environment)
        .bind("vanillabp.adapters.%s".formatted(adapterId), Camunda7EngineProperties.class)
        .orElseGet(Camunda7EngineProperties::new);

  }

  /**
   * Resolves an application-provided bean with a GUIDING failure instead of a
   * bean-wiring error: the embedded engine needs a {@code DataSource} and a
   * {@code PlatformTransactionManager} - a configured adapter without a database is
   * a configuration defect the developer has to learn about with the remedy named,
   * not via a {@code NoSuchBeanDefinitionException}.
   *
   * @param <S> The bean type
   * @param supplierContext The bean supplier context
   * @param beanType The bean type
   * @param adapterId The adapter ID (used in the guiding message)
   * @return The bean
   */
  private static <S> S applicationBean(
      final BeanRegistry.SupplierContext supplierContext,
      final Class<S> beanType,
      final String adapterId) {

    final var bean = supplierContext
        .beanProvider(beanType)
        .getIfAvailable();
    if (bean == null) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' is configured ('vanillabp.adapters.%s.type: camunda7') but the \
              embedded engine cannot be created: no DataSource/PlatformTransactionManager is available. \
              Camunda 7 runs embedded and always needs a database - configure 'spring.datasource.*' \
              (and a transaction manager, e.g. via the JDBC or JPA starters), reference an \
              application-provided DataSource bean via 'vanillabp.adapters.%s.data-source-name' or \
              remove the adapter configuration."""
              .formatted(adapterId, adapterId, adapterId));
    }
    return bean;

  }

  /**
   * Fails the boot if more than one {@code camunda7} adapter id resolves to the same
   * datasource: two embedded engines on one schema are the same engine state -
   * configuring them as two adapters is an error (validated at startup, 26c style).
   */
  private static void validateDistinctDataSources(
      final Environment environment,
      final List<String> camunda7AdapterIds) {

    if (camunda7AdapterIds.size() < 2) {
      return;
    }

    final var idsByDataSource = new LinkedHashMap<String, List<String>>();
    camunda7AdapterIds
        .forEach(adapterId -> {
          final var dataSourceName = Binder
              .get(environment)
              .bind(
                  "vanillabp.adapters.%s.data-source-name".formatted(adapterId),
                  Bindable.of(String.class))
              .orElse(null);
          final var effectiveDataSource = (dataSourceName != null) && !dataSourceName.isBlank()
              ? dataSourceName
              : "<the application's default datasource>";
          idsByDataSource
              .computeIfAbsent(effectiveDataSource, key -> new LinkedList<>())
              .add(adapterId);
        });

    idsByDataSource
        .forEach((
            dataSource,
            adapterIds) -> {
          if (adapterIds.size() < 2) {
            return;
          }
          throw new IllegalStateException(
              """
                  The Camunda 7 adapters '%s' would share the same datasource (%s)! Two embedded \
                  engines on one schema are the same engine state - configuring them as separate \
                  adapters is an error. Give each additional adapter its own schema: define a \
                  DataSource bean in your application and reference it via \
                  'vanillabp.adapters.<id>.data-source-name', or remove all but one of these adapters."""
                  .formatted(String.join("', '", adapterIds), dataSource));
        });

  }

}
