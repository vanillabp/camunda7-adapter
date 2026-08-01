package io.vanillabp.camunda7.engine;

/**
 * Per-adapter-id engine settings of the Camunda 7 adapter, living at the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code> (contributed to
 * the shared configuration tree via the platform-specific overlay - see the Spring
 * Boot module's <code>VanillaBpCamunda7Properties</code> and the Quarkus module's
 * <code>@ConfigMapping</code> overlay):
 * <ul>
 *   <li><code>database-schema-update</code> - create/upgrade the engine schema on
 *       boot (engine values, e.g. <code>true</code>, <code>false</code>,
 *       <code>create-drop</code>); default <code>true</code>;</li>
 *   <li><code>history-time-to-live</code> - engine-wide default history time to
 *       live (Camunda 7.24 rejects deployments of processes without one); default
 *       <code>P180D</code>, overridable per process via
 *       <code>camunda:historyTimeToLive</code>;</li>
 *   <li><code>data-source-name</code> - OPTIONAL name of an
 *       application-/runtime-provided datasource this adapter id's embedded engine
 *       runs on (setting up datasources is deliberately NOT VanillaBP's concern -
 *       the adapter never builds its own pool). Spring Boot: the name of a
 *       {@code DataSource} BEAN; Quarkus: the name of a declared named Agroal
 *       datasource (<code>quarkus.datasource.&lt;name&gt;.*</code>). Without it
 *       the engine shares the application's default datasource and joins the
 *       caller's transaction (the embedded-engine guarantee). With it the engine
 *       runs on its own schema - required for engine-side-by-side migrations (two
 *       embedded engines must never share one schema) - and starting workflows
 *       uses VanillaBP's two-phase pattern (see
 *       <code>Camunda7ProcessService</code>).</li>
 * </ul>
 */
public class Camunda7EngineProperties {

  private String databaseSchemaUpdate = "true";

  private String historyTimeToLive = "P180D";

  private String dataSourceName;

  public String getDatabaseSchemaUpdate() {
    return databaseSchemaUpdate;
  }

  public void setDatabaseSchemaUpdate(
      final String databaseSchemaUpdate) {
    this.databaseSchemaUpdate = databaseSchemaUpdate;
  }

  public String getHistoryTimeToLive() {
    return historyTimeToLive;
  }

  public void setHistoryTimeToLive(
      final String historyTimeToLive) {
    this.historyTimeToLive = historyTimeToLive;
  }

  public String getDataSourceName() {
    return dataSourceName;
  }

  public void setDataSourceName(
      final String dataSourceName) {
    this.dataSourceName = dataSourceName;
  }

  /**
   * @return Whether an own (named) datasource is configured for the adapter id
   */
  public boolean usesSeparateDataSource() {
    return (dataSourceName != null) && !dataSourceName.isBlank();
  }

}
