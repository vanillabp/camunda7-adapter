package io.vanillabp.camunda7.springboot.webapps;

import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;

/**
 * Serves Camunda's web applications at <code>/camunda</code> against the engines
 * VanillaBP built.
 *
 * <p>
 * Two things have to be arranged for that, and neither is obvious:
 * </p>
 *
 * <ol>
 * <li>Camunda's webapp auto-configuration only applies when a {@link CamundaBpmProperties}
 * bean exists, which normally comes with their engine auto-configuration. That one is
 * switched off here (see {@link Camunda7WebappsEnvironmentPostProcessor}), because it
 * would build a second engine, so this module contributes the properties bean itself.
 * Its defaults are what the webapps need, including the path <code>/camunda</code>.</li>
 * <li>The webapps resolve engines through the runtime container rather than through
 * beans, so {@link Camunda7WebappsRegistration} registers VanillaBP's engines there.</li>
 * </ol>
 */
@AutoConfiguration(after = Camunda7ProcessServiceConfiguration.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties({
    CamundaBpmProperties.class, Camunda7WebappsProperties.class
})
public class Camunda7WebappsAutoConfiguration {

  @Bean
  public Camunda7WebappsRegistration vanillaBpCamunda7WebappsRegistration(
      final ObjectProvider<Camunda7EngineHolder> engines,
      final Camunda7WebappsProperties properties) {

    return new Camunda7WebappsRegistration(engines, properties);

  }

}
