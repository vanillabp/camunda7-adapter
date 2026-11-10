package io.vanillabp.camunda7.quarkus.plugins;

import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * An engine plugin of the application, configured under {@code engine-plugins}, which
 * changes a setting the adapter leaves alone.
 */
public class RetriesPlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(
      final ProcessEngineConfigurationImpl configuration) {
    configuration.setDefaultNumberOfRetries(5);
  }

}
