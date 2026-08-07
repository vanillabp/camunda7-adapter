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
 *   <li><code>table-prefix</code> - OPTIONAL prefix of the engine's database tables
 *       (engine setting <code>databaseTablePrefix</code>). It lets two adapter ids
 *       share ONE datasource while running separate engines - exactly the
 *       side-by-side migration setup on a single database. The tables of each
 *       prefix have to exist (the engine's schema creation honors the prefix, so
 *       <code>database-schema-update</code> creates them; a prefix pointing at a
 *       schema, e.g. <code>MY_SCHEMA.</code>, requires that schema to exist).</li>
 * </ul>
 */
public class Camunda7EngineProperties {

  private String databaseSchemaUpdate = "true";

  private String historyTimeToLive = "P180D";

  private String dataSourceName;

  private String tablePrefix;

  /**
   * The Camunda tenant a workflow module is deployed to under the name-clash
   * avoidance mode {@code by-adapter} (story 35). Unset (the default) means the
   * workflow module ID is the tenant - VanillaBP 1's behavior.
   */
  private String tenantId;

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

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(
      final String tenantId) {
    this.tenantId = tenantId;
  }

  public String getTablePrefix() {
    return tablePrefix;
  }

  public void setTablePrefix(
      final String tablePrefix) {
    this.tablePrefix = tablePrefix;
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
  /**
   * The reserved value of {@code data-source-name} naming the application's
   * DEFAULT datasource explicitly. Needed because an application providing several
   * datasources has to name the one each adapter id runs on (story 34) - and the
   * default datasource has no name of its own on either platform.
   */
  public static final String DEFAULT_DATA_SOURCE_NAME = "default";

  /**
   * @param dataSourceName A configured datasource name
   * @return Whether it names the application's DEFAULT datasource
   */
  public static boolean isDefaultDataSourceName(
      final String dataSourceName) {

    return (dataSourceName == null) || dataSourceName.isBlank() || DEFAULT_DATA_SOURCE_NAME
        .equalsIgnoreCase(dataSourceName.trim());

  }

  public boolean usesSeparateDataSource() {
    return !isDefaultDataSourceName(dataSourceName);
  }

}
