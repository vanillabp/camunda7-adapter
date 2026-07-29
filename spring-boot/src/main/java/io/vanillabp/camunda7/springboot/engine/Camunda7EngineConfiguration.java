package io.vanillabp.camunda7.springboot.engine;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguredCondition;

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
 *   <li>{@code databaseSchemaUpdate} - create/upgrade the engine schema on boot; defaults
 *       to {@code true}, configurable via
 *       {@code vanillabp.adapters.<id>.database-schema-update} for manually managed
 *       schemas;</li>
 *   <li>{@code jobExecutorActivate = true} - run asynchronous continuations (async-before/
 *       after, timers) on the engine's job executor.</li>
 * </ul>
 * The configuration is only active if at least one adapter of type {@code camunda7} is
 * configured ({@link Camunda7AdapterConfiguredCondition} - without the gate ANY
 * application having the jar plus a data source would get {@code ACT_*} tables and a
 * running job executor in its business database) and if a {@link DataSource} and a
 * {@link PlatformTransactionManager} are available - a Camunda 7 application always
 * needs a database.
 * <p>
 * The transaction-manager auto-configurations are listed in {@code afterName} so
 * {@code @ConditionalOnBean(PlatformTransactionManager.class)} cannot evaluate before
 * the transaction manager is registered - including plain-JDBC applications without
 * JPA ({@code DataSourceTransactionManagerAutoConfiguration}).
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration", "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@Conditional(Camunda7AdapterConfiguredCondition.class)
@ConditionalOnBean({
    DataSource.class, PlatformTransactionManager.class
})
public class Camunda7EngineConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SpringProcessEngineConfiguration camunda7ProcessEngineConfiguration(
      final DataSource dataSource,
      final PlatformTransactionManager transactionManager,
      final Environment environment) {

    // canonical per-adapter location (see the VanillaBP configuration model); the
    // engine is not per-adapter-id yet (story 26e) - until then the first
    // configured camunda7 adapter id's setting is used
    final var adapterId = Camunda7AdapterConfiguredCondition
        .firstCamunda7AdapterId(environment)
        .orElseThrow();
    final var databaseSchemaUpdate = Binder
        .get(environment)
        .bind("vanillabp.adapters.%s.database-schema-update".formatted(adapterId), String.class)
        .orElse("true");

    final var configuration = new SpringProcessEngineConfiguration();
    configuration.setProcessEngineName("vanillabp-camunda7");
    configuration.setDataSource(dataSource);
    configuration.setTransactionManager(transactionManager);
    configuration.setDatabaseSchemaUpdate(databaseSchemaUpdate);
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
