package io.vanillabp.camunda7.springboot;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * The Camunda 7 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree: the adapter's engine settings live at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.*</code> (keys documented in
 * {@link Camunda7EngineProperties}: <code>database-schema-update</code>,
 * <code>history-time-to-live</code>, <code>data-source.url</code>,
 * <code>data-source.username</code>, <code>data-source.password</code>,
 * <code>data-source.driver-class-name</code>). A second
 * {@code @ConfigurationProperties} class over the same prefix coexists with the
 * platform's binding of the core model; keys unknown to either view are ignored by
 * the JavaBean binding.
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type
 * {@code camunda7}); the overlay is a per-known-id lookup only (environment-variable
 * overrides can materialize phantom map entries in the overlay).
 */
@ConfigurationProperties("vanillabp")
@Getter
@Setter
public class VanillaBpCamunda7Properties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the
   * Camunda 7 engine keys are modeled here (bound directly onto the
   * platform-neutral {@link Camunda7EngineProperties}).
   */
  private Map<String, Camunda7EngineProperties> adapters = Map.of();

  /**
   * The engine settings of an adapter id, defaults if the section is absent.
   *
   * @param adapterId The adapter id
   * @return The engine settings (never <code>null</code>)
   */
  public Camunda7EngineProperties enginePropertiesFor(
      final String adapterId) {

    final var properties = adapters.get(adapterId);
    return properties != null
        ? properties
        : new Camunda7EngineProperties();

  }

}
