package io.vanillabp.camunda7.it;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.persistence.StrongUuidGenerator;

/**
 * A plain engine set up like the one version 1 ran on. Version 1 had its engine built by
 * Camunda's Spring Boot starter, which writes history at the level <code>full</code> and makes
 * UUIDs as ids. The classes of version 1 cannot be on this class path. What version 2 finds in
 * the database is the same, because the starter only built the engine.
 * <p>
 * The engine builds the tables if they are missing and runs no jobs. The caller closes it.
 */
final class VersionOneEngine {

  private VersionOneEngine() {
  }

  /**
   * Builds the engine on an in-memory H2 database which outlives the engine.
   *
   * @param database The name of the H2 database
   * @param engineName The name of the engine, which has to differ from every other engine of the
   *          test run
   * @return The engine, which the caller has to close
   */
  static ProcessEngine on(
      final String database,
      final String engineName) {

    final var configuration = (ProcessEngineConfigurationImpl) new StandaloneProcessEngineConfiguration()
        .setJdbcUrl("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database))
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
        .setHistory(ProcessEngineConfiguration.HISTORY_FULL)
        .setJobExecutorActivate(false)
        .setProcessEngineName(engineName);
    configuration.setIdGenerator(new StrongUuidGenerator());
    // the engine refuses a model without a history time to live
    configuration.setHistoryTimeToLive("P1D");
    return configuration.buildProcessEngine();

  }

}
