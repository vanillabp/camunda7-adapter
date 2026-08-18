package io.vanillabp.camunda7.quarkus.runtime;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The Camunda 7 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree: the adapter's engine settings live at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.*</code>. A second RUN_TIME
 * {@code @ConfigMapping} over the same prefix coexists with the platform's mapping;
 * since the platform dropped the blanket {@code withMappingIgnore}, this overlay
 * doubles as the unknown-key validation coverage for the adapter's keys.
 * <p>
 * The keys are IDENTICAL to the Spring Boot module: an adapter id's own datasource is
 * referenced BY NAME on both platforms (<code>data-source-name</code>; always
 * application-/runtime-provided - VanillaBP never builds a pool). On Quarkus the name
 * points at a declared <code>quarkus.datasource.&lt;name&gt;.*</code> datasource, on
 * Spring Boot at a {@code DataSource} bean.
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type
 * {@code camunda7}); the overlay is a per-known-id lookup only.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpCamunda7Properties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the
   * Camunda 7 engine keys are modeled here.
   */
  Map<String, Camunda7AdapterKeys> adapters();

  /**
   * The workflow-module sections of the shared tree - only the Camunda 7 keys which are
   * resolvable per scope are modeled here (story 66: the serialization format).
   */
  Map<String, Camunda7WorkflowModuleKeys> workflowModules();

  /**
   * The Camunda 7 keys of one <code>vanillabp.workflow-modules.&lt;module&gt;</code>
   * section which may override what the adapter section says.
   */
  interface Camunda7WorkflowModuleKeys {

    /**
     * The per-adapter-id overrides of this workflow module.
     */
    Map<String, Camunda7ScopedKeys> adapters();

    /**
     * The workflows of this workflow module, keyed by BPMN process ID.
     */
    Map<String, Camunda7WorkflowKeys> workflows();

  }

  /**
   * The Camunda 7 keys of one workflow.
   */
  interface Camunda7WorkflowKeys {

    /**
     * The per-adapter-id overrides of this workflow - the most specific level.
     */
    Map<String, Camunda7ScopedKeys> adapters();

  }

  /**
   * One engine plugin of an adapter id (story 66).
   */
  interface Camunda7EnginePluginKeys {

    /**
     * The plugin's class, e.g. <code>org.camunda.xstream.ProcessEnginePlugin</code>.
     */
    Optional<String> pluginClass();

    /**
     * The plugin's own properties in kebab-case - Camunda converts them to the types the
     * plugin declares.
     */
    Map<String, String> properties();

  }

  /**
   * The Camunda 7 keys which may be set per workflow module and per workflow.
   */
  interface Camunda7ScopedKeys {

    /**
     * The serialization format of nested shared values for this scope (story 66).
     */
    Optional<String> serializationFormat();


  }

  /**
   * The Camunda 7 engine keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  interface Camunda7AdapterKeys {

    /**
     * Create/upgrade the engine schema on boot (engine values, e.g.
     * <code>true</code>, <code>false</code>, <code>create-drop</code>); default
     * <code>true</code>.
     */
    Optional<String> databaseSchemaUpdate();

    /**
     * Engine-wide default history time to live (Camunda 7.24 rejects deployments
     * of processes without one); default <code>P180D</code>, overridable per
     * process via <code>camunda:historyTimeToLive</code>.
     */
    Optional<String> historyTimeToLive();

    /**
     * OPTIONAL name of a declared Quarkus datasource
     * (<code>quarkus.datasource.&lt;name&gt;.*</code>) this adapter id's embedded
     * engine runs on. Without it the engine shares the application's default
     * datasource (the embedded-engine guarantee: engine commands join the caller's
     * JTA transaction). With it the engine runs on its own schema - required for
     * engine-side-by-side migrations - and starting workflows uses VanillaBP's
     * two-phase pattern (see the README's transaction caveat).
     */
    Optional<String> dataSourceName();

    /**
     * OPTIONAL prefix of the engine's database tables (engine setting
     * <code>databaseTablePrefix</code>). It lets two adapter ids share ONE
     * datasource while running separate engines - the side-by-side migration setup
     * on a single database. The tables of each prefix have to exist (the engine's
     * schema creation honors the prefix).
     */
    Optional<String> tablePrefix();

    /**
     * OPTIONAL serialization format nested values shared by a workflow aggregate are
     * stored in (story 66), e.g. <code>application/xstream</code> (camunda-xstream) or
     * <code>application/json</code> (SPIN). Applied to the engine's
     * <code>defaultSerializationFormat</code> and to the variables VanillaBP writes;
     * overridable per workflow module and per workflow (see
     * {@link VanillaBpCamunda7Properties#workflowModules()}). The matching dataformat is
     * the application's dependency.
     */
    Optional<String> serializationFormat();

    /**
     * OPTIONAL Camunda engine plugins of this adapter id: named sections, each naming a
     * class and carrying its own properties - which Camunda applies, exactly like the
     * <code>&lt;property&gt;</code> elements of a <code>bpm-platform.xml</code> (story 66).
     * This is how a serialization dataformat reaches the embedded engine.
     */
    Map<String, Camunda7EnginePluginKeys> enginePlugins();

    /**
     * OPTIONAL name of the Camunda tenant a workflow module is deployed to under the
     * name-clash-avoidance mode <code>by-adapter</code> (story 35). Without it the
     * workflow module ID names the tenant - VanillaBP 1's behavior.
     */
    Optional<String> tenantId();

    /**
     * OPTIONAL acknowledgement that the application's identifiers are unique across all
     * of its workflow modules - it silences the WARN logged while the
     * name-clash-avoidance mode <code>none</code> applies (this adapter's default
     * mode). Default <code>false</code>.
     */
    Optional<Boolean> acceptUnscopedIdentifiers();

  }

}
