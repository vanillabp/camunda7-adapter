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

  }

}
