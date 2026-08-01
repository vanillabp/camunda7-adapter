package io.vanillabp.camunda7.engine;

/**
 * Per-adapter-id engine settings of the Camunda 7 adapter, living at the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code> (contributed to
 * the shared configuration tree via the platform-specific overlay - see the Spring
 * Boot module's <code>VanillaBpCamunda7Properties</code>):
 * <ul>
 *   <li><code>database-schema-update</code> - create/upgrade the engine schema on
 *       boot (engine values, e.g. <code>true</code>, <code>false</code>,
 *       <code>create-drop</code>); default <code>true</code>;</li>
 *   <li><code>history-time-to-live</code> - engine-wide default history time to
 *       live (Camunda 7.24 rejects deployments of processes without one); default
 *       <code>P180D</code>, overridable per process via
 *       <code>camunda:historyTimeToLive</code>;</li>
 *   <li><code>data-source.*</code> - OPTIONAL own datasource of this adapter id's
 *       embedded engine (<code>url</code>, <code>username</code>,
 *       <code>password</code>, <code>driver-class-name</code>). Without it the
 *       engine shares the application's datasource and transaction manager (the
 *       embedded-engine guarantee). With it the adapter owns a separate pool plus
 *       transaction manager for that engine - required for engine-side-by-side
 *       migrations (two embedded engines must never share one schema), at the price
 *       of the engine no longer joining the caller's transaction (such adapter ids
 *       use VanillaBP's two-phase start instead, see
 *       <code>Camunda7ProcessService</code>).</li>
 * </ul>
 */
public class Camunda7EngineProperties {

  /**
   * Connection settings of an adapter id's own engine datasource.
   */
  public static class EngineDataSource {

    private String url;

    private String username;

    private String password;

    private String driverClassName;

    public String getUrl() {
      return url;
    }

    public void setUrl(
        final String url) {
      this.url = url;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(
        final String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(
        final String password) {
      this.password = password;
    }

    public String getDriverClassName() {
      return driverClassName;
    }

    public void setDriverClassName(
        final String driverClassName) {
      this.driverClassName = driverClassName;
    }

    /**
     * @return Whether an own datasource is configured for the adapter id (the
     *         <code>url</code> is the discriminating key)
     */
    public boolean isConfigured() {
      return (url != null) && !url.isBlank();
    }

  }

  private String databaseSchemaUpdate = "true";

  private String historyTimeToLive = "P180D";

  private EngineDataSource dataSource = new EngineDataSource();

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

  public EngineDataSource getDataSource() {
    return dataSource;
  }

  public void setDataSource(
      final EngineDataSource dataSource) {
    this.dataSource = dataSource;
  }

}
