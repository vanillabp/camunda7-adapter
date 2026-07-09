package io.vanillabp.camunda7.springboot.engine;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the <b>embedded Camunda 7 engine</b> so it shares the application's
 * {@link DataSource} and Spring {@link PlatformTransactionManager}. This is the whole
 * point of the Camunda 7 adapter: because the engine uses the caller's transaction,
 * starting a workflow (and any engine state change) is committed or rolled back together
 * with the business data.
 * <p>
 * We do not use {@code camunda-bpm-spring-boot-starter} (it targets Spring Boot 3.5.x and
 * is incompatible with the Spring Boot 4.1 baseline). Instead the engine is built directly
 * from {@link SpringProcessEngineConfiguration} (shipped by {@code camunda-engine-spring-6},
 * whose Spring dependencies are {@code provided} so the application's Spring 7 is used).
 * <ul>
 *   <li>{@code dataSource} - the application's data source, so the engine tables (ACT_*)
 *       live next to the workflow aggregates and share transactions;</li>
 *   <li>{@code transactionManager} - the application's transaction manager, so engine
 *       commands join the caller's transaction;</li>
 *   <li>{@code databaseSchemaUpdate = true} - create/upgrade the engine schema on boot;</li>
 *   <li>{@code jobExecutorActivate = true} - run asynchronous continuations (async-before/
 *       after, timers) on the engine's job executor.</li>
 * </ul>
 * The configuration is only active if a {@link DataSource} and a
 * {@link PlatformTransactionManager} are available - a Camunda 7 application always needs
 * a database.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@ConditionalOnBean({
    DataSource.class, PlatformTransactionManager.class
})
public class Camunda7EngineConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SpringProcessEngineConfiguration camunda7ProcessEngineConfiguration(
      final DataSource dataSource,
      final PlatformTransactionManager transactionManager) {

    final var configuration = new SpringProcessEngineConfiguration();
    configuration.setProcessEngineName("vanillabp-camunda7");
    configuration.setDataSource(dataSource);
    configuration.setTransactionManager(transactionManager);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setJobExecutorActivate(true);
    // Camunda 7.24 rejects deployments of processes without a history-time-to-live. Provide
    // an engine-wide default so BPMN models need not declare it individually (a process may
    // still override it via camunda:historyTimeToLive).
    configuration.setHistoryTimeToLive("P180D");
    return configuration;

  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public ProcessEngine camunda7ProcessEngine(
      final SpringProcessEngineConfiguration camunda7ProcessEngineConfiguration) {

    return camunda7ProcessEngineConfiguration.buildProcessEngine();

  }

  @Bean
  @ConditionalOnMissingBean
  public RepositoryService camunda7RepositoryService(
      final ProcessEngine camunda7ProcessEngine) {

    return camunda7ProcessEngine.getRepositoryService();

  }

  @Bean
  @ConditionalOnMissingBean
  public RuntimeService camunda7RuntimeService(
      final ProcessEngine camunda7ProcessEngine) {

    return camunda7ProcessEngine.getRuntimeService();

  }

}
