package io.vanillabp.camunda7.quarkus.plugins;

import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * An engine plugin of the application, configured under {@code engine-plugins}, which
 * switches off the deployment awareness the adapter sets. Unlike
 * {@link NotDeploymentAwarePlugin} it is no CDI bean, so the engine gets it only once.
 */
public class NotDeploymentAwareConfiguredPlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(
      final ProcessEngineConfigurationImpl configuration) {
    configuration.setJobExecutorDeploymentAware(false);
  }

}
