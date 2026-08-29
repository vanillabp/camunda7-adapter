package io.vanillabp.camunda7.springboot;

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
import io.vanillabp.camunda7.engine.Camunda7InstanceIdentity;
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
 * state. That check runs through the adapter SPI hook
 * {@code AdapterDeploymentService#validateDistinctAdapterInstances} (implemented ONCE
 * in {@link Camunda7DeploymentService} for both platforms): adapter ids sharing one
 * datasource AND one table prefix fail the boot with a guiding message. An
 * application providing SEVERAL datasources additionally has to name the one an
 * adapter id runs on (see {@link #applicationBean}).
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
                                    .bean(WorkflowTaskInvoker.class), supplierContext
                                        .beanProvider(
                                            io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker.class)
                                        .getIfAvailable(), supplierContext
                                            .beanProvider(
                                                io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker.class)
                                            .getIfAvailable());
              }));

          registry.registerBean(
              "Camunda7_ProcessService_%s".formatted(adapterId),
              Camunda7ProcessService.class,
              spec -> spec.supplier(supplierContext -> {
                final var engine = engineHolder(supplierContext, adapterId);
                final var processService = new Camunda7ProcessService<>(
                    adapterId, engine.getRuntimeService(), engine.getTaskService(), engine
                        .getRepositoryService(), engine.getHistoryService(), AdapterBeanRegistrarSupport
                            .collaborators(supplierContext, adapterId));
                processService
                    .setConfiguredTenantId(
                        configuredTenantIdOf(supplierContext.bean(VanillaBpCamunda7Properties.class), adapterId));
                // Which serialization format nested shared values are stored
                // in, resolved per workflow with a fallback to the module and the adapter
                final var overlay = supplierContext.bean(VanillaBpCamunda7Properties.class);
                processService.setSerializationFormats(
                    (
                        workflowModuleId,
                        bpmnProcessId) -> overlay
                            .serializationFormatFor(adapterId, workflowModuleId, bpmnProcessId));
                engine
                    .getTaskRegistry()
                    .setSerializationFormats(
                        (
                            workflowModuleId,
                            bpmnProcessId) -> overlay
                                .serializationFormatFor(adapterId, workflowModuleId, bpmnProcessId));
                return processService;
              }));

          registry.registerBean(
              "Camunda7_DeploymentService_%s".formatted(adapterId),
              Camunda7DeploymentService.class,
              spec -> spec.supplier(supplierContext -> {
                final var engine = engineHolder(supplierContext, adapterId);
                final var deploymentService = new Camunda7DeploymentService(
                    adapterId, engine.getRepositoryService(), engine, AdapterBeanRegistrarSupport
                        .collaborators(supplierContext, adapterId), engine.getTaskRegistry(), id -> instanceIdentityOf(
                            environment, id));
                deploymentService.setEngineDeliversWorkflowEnded(engine.deliversWorkflowEnded());
                deploymentService.setConfiguredTenantId(
                    configuredTenantIdOf(supplierContext.bean(VanillaBpCamunda7Properties.class), adapterId));
                deploymentService.setIdentityService(
                    engine
                        .getProcessEngine()
                        .getIdentityService());
                // How many workflows still run on an older version
                deploymentService.setRuntimeService(
                    engine
                        .getProcessEngine()
                        .getRuntimeService());
                deploymentService.setAcceptUnscopedIdentifiers(
                    supplierContext
                        .bean(VanillaBpCamunda7Properties.class)
                        .enginePropertiesFor(adapterId)
                        .isAcceptUnscopedIdentifiers());
                return deploymentService;
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

    // several datasources available: which one the engine runs on is not VanillaBP's
    // guess - not even a @Primary bean decides it, because an embedded
    // engine writes its ACT_* tables into whatever database it gets
    final var availableBeanNames = availableBeanNames(supplierContext, beanType);
    if ((availableBeanNames.size() > 1) && DataSource.class.equals(beanType)) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' runs embedded and needs a database, but the application \
              provides SEVERAL DataSource beans: '%s'. Name the one this adapter id runs on:
                vanillabp.adapters.%s.data-source-name: <bean name>
              Use the reserved value 'default' for the application's default (primary) datasource. \
              (Two adapter ids may also share one datasource if each uses its own \
              'vanillabp.adapters.<id>.table-prefix' - Camunda does not create prefixed tables, so \
              those have to exist beforehand.)"""
              .formatted(adapterId, String.join("', '", availableBeanNames), adapterId));
    }

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
   * What makes an adapter id a distinct engine: its datasource and table prefix
   * (see {@link Camunda7InstanceIdentity}). Read from the environment because the
   * check runs before/independently of the engine beans.
   */
  private static Camunda7InstanceIdentity instanceIdentityOf(
      final Environment environment,
      final String adapterId) {

    return new Camunda7InstanceIdentity(
        adapterProperty(environment, adapterId, "data-source-name"), adapterProperty(environment, adapterId,
            "table-prefix"));

  }

  private static String adapterProperty(
      final Environment environment,
      final String adapterId,
      final String key) {

    return Binder
        .get(environment)
        .bind(
            "vanillabp.adapters.%s.%s".formatted(adapterId, key),
            Bindable.of(String.class))
        .orElse(null);

  }


  /**
   * The NAMES of all beans of the given type - looked up without instantiating them
   * (bean types only). An empty list if the bean factory cannot be reached, in
   * which case the ambiguity check simply does not fire.
   */
  private static List<String> availableBeanNames(
      final BeanRegistry.SupplierContext supplierContext,
      final Class<?> beanType) {

    try {
      return List.of(
          supplierContext
              .bean(org.springframework.context.ApplicationContext.class)
              .getBeanNamesForType(beanType, true, false));
    } catch (final RuntimeException e) {
      return List.of();
    }

  }


  /**
   * The tenant name configured for an adapter id
   * (<code>vanillabp.adapters.&lt;id&gt;.tenant-id</code>) or <code>null</code> - the
   * workflow module id names the tenant then.
   */
  private static String configuredTenantIdOf(
      final VanillaBpCamunda7Properties overlay,
      final String adapterId) {

    final var adapter = overlay
        .getAdapters()
        .get(adapterId);
    return adapter != null
        ? adapter.getTenantId()
        : null;

  }

}
