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
 * <code>history-time-to-live</code>, <code>data-source-name</code> - on Spring Boot
 * the name of an application-provided {@code DataSource} BEAN). A second
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
   * The workflow-module sections of the shared tree - only the Camunda 7 keys resolvable
   * per scope are modeled here (the serialization format of nested shared values).
   */
  private Map<String, Camunda7WorkflowModuleProperties> workflowModules = Map.of();

  /**
   * The Camunda 7 keys of one <code>vanillabp.workflow-modules.&lt;module&gt;</code>
   * section which may override what the adapter section says.
   */
  @Getter
  @Setter
  public static class Camunda7WorkflowModuleProperties {

    private Map<String, Camunda7ScopedProperties> adapters = Map.of();

    private Map<String, Camunda7WorkflowProperties> workflows = Map.of();

  }

  /**
   * The Camunda 7 keys of one workflow.
   */
  @Getter
  @Setter
  public static class Camunda7WorkflowProperties {

    private Map<String, Camunda7ScopedProperties> adapters = Map.of();

  }

  /**
   * The Camunda 7 keys which may be set per workflow module and per workflow.
   */
  @Getter
  @Setter
  public static class Camunda7ScopedProperties {

    /**
     * The serialization format of nested shared values for this scope.
     */
    private String serializationFormat;

  }

  /**
   * The serialization format configured for one workflow, most specific first: the
   * workflow, its workflow module, the adapter.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The format or <code>null</code> where none is configured
   */
  public String serializationFormatFor(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    return io.vanillabp.camunda7.sync.Camunda7SerializationFormats
        .firstConfigured(
            scopedFormat(workflow != null
                ? workflow.getAdapters()
                : null, adapterId),
            scopedFormat(module != null
                ? module.getAdapters()
                : null, adapterId),
            enginePropertiesFor(adapterId).getSerializationFormat());

  }

  private static String scopedFormat(
      final Map<String, Camunda7ScopedProperties> adapters,
      final String adapterId) {

    final var scoped = adapters != null
        ? adapters.get(adapterId)
        : null;
    return scoped != null
        ? scoped.getSerializationFormat()
        : null;

  }

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
