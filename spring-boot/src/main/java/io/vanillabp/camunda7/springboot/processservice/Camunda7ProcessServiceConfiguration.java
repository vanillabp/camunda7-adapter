package io.vanillabp.camunda7.springboot.processservice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

import io.vanillabp.camunda7.springboot.Camunda7AdapterBeanRegistrar;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineConfiguration;

/**
 * Wires the Camunda 7 adapter's process-service and deployment-service beans: ONE
 * element bean per configured adapter id of type {@code camunda7}, registered by the
 * imported {@link Camunda7AdapterBeanRegistrar}. Without any configured
 * {@code camunda7} adapter id no bean is registered (the registrar derives the id set
 * from the configuration), so no {@code @Conditional} gating is needed here.
 */
@AutoConfiguration(after = Camunda7EngineConfiguration.class)
@Import(Camunda7AdapterBeanRegistrar.class)
public class Camunda7ProcessServiceConfiguration {

}
