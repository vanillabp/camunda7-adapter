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
                  adapterId, supplierContext.bean(RuntimeService.class))));

          registry.registerBean(
              "Camunda7_DeploymentService_%s".formatted(adapterId),
              Camunda7DeploymentService.class,
              spec -> spec.supplier(supplierContext -> new Camunda7DeploymentService(
                  adapterId, supplierContext.bean(RepositoryService.class))));

        });

  }

}
