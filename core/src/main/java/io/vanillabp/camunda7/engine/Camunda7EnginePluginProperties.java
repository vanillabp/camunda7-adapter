package io.vanillabp.camunda7.engine;

import java.util.Map;

/**
 * One engine plugin of an adapter id (story 66): which class, and the properties Camunda
 * applies to it.
 * <p>
 * The section is NAMED by the application rather than keyed by the class, because a class
 * name carries dots - which both configuration binders would need quoted:
 *
 * <pre>
 * vanillabp:
 *   adapters:
 *     camunda7:
 *       engine-plugins:
 *         xstream:
 *           plugin-class: org.camunda.xstream.ProcessEnginePlugin
 *           properties:
 *             encoding: UTF-8
 *             allowed-types: my.project.**,other.project.**
 * </pre>
 */
public class Camunda7EnginePluginProperties {

  private String pluginClass;

  private Map<String, String> properties = Map.of();

  public String getPluginClass() {
    return pluginClass;
  }

  public void setPluginClass(
      final String pluginClass) {
    this.pluginClass = pluginClass;
  }

  public Map<String, String> getProperties() {
    return properties;
  }

  public void setProperties(
      final Map<String, String> properties) {
    this.properties = properties == null
        ? Map.of()
        : properties;
  }

}
