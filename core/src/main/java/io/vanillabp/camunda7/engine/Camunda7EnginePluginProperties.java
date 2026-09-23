package io.vanillabp.camunda7.engine;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * One engine plugin of an adapter id: which class, and the properties Camunda
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
@Getter
@Setter
public class Camunda7EnginePluginProperties {

  /**
   * The platform integration builds one per configured plugin section and fills it from
   * the keys it found.
   */
  public Camunda7EnginePluginProperties() {

  }

  private String pluginClass;

  private Map<String, String> properties = Map.of();

  /**
   * Keeps an empty map instead of <code>null</code>, so a plugin section which names a
   * class and nothing else is applied rather than failing when Camunda reads it.
   *
   * @param properties The plugin's own properties, may be <code>null</code>
   */
  public void setProperties(
      final Map<String, String> properties) {
    this.properties = properties == null
        ? Map.of()
        : properties;
  }

}
