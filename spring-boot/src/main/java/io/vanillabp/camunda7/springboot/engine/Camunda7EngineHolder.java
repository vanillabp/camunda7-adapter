package io.vanillabp.camunda7.springboot.engine;

import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.camunda.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns ONE embedded Camunda 7 engine per configured adapter id (engine name
 * <code>vanillabp-camunda7-&lt;id&gt;</code>) including its complete lifecycle:
 * <ul>
 *   <li><b>Datasource:</b> by default the engine shares the application's
 *       {@link DataSource} and {@link PlatformTransactionManager} - the whole point
 *       of the embedded adapter (engine state commits/rolls back with the business
 *       data). With <code>vanillabp.adapters.&lt;id&gt;.data-source.*</code>
 *       configured, the holder builds an OWN Hikari pool plus
 *       {@link DataSourceTransactionManager} for this engine (engine-side-by-side
 *       migration: two embedded engines must never share one schema) and owns that
 *       pool's lifecycle - created with the engine, closed after the engine
 *       closed.</li>
 *   <li><b>Job executor:</b> an idiomatic {@link SpringJobExecutor} backed by a
 *       dedicated {@link ThreadPoolTaskExecutor} (thread-name prefix contains the
 *       adapter id) instead of the engine-default {@code DefaultJobExecutor} with
 *       its raw unmanaged threads. Activation is DEFERRED: the engine is built with
 *       {@code jobExecutorActivate=false}; the deployment pipeline's
 *       {@code startWorkflowProcessing} starts the executor once the first workflow
 *       module starts. Since the executor is engine-global while start/stop is
 *       notified per module, the started modules are reference-counted: the
 *       executor stops when the LAST started module stops (stopping on the first
 *       module's stop would starve the remaining modules) - and unconditionally on
 *       {@link #close()}, before the engine closes.</li>
 * </ul>
 */
@Slf4j
public class Camunda7EngineHolder implements Camunda7WorkflowProcessingLifecycle, ApplicationContextAware, InitializingBean, AutoCloseable {

  private final String adapterId;

  private final Camunda7EngineProperties properties;

  private final DataSource applicationDataSource;

  private final PlatformTransactionManager applicationTransactionManager;

  /**
   * Required by {@code SpringProcessEngineConfiguration} (Spring-bean resolution in
   * scripting/expressions) - injected via {@link ApplicationContextAware}; the
   * engine is built in {@link #afterPropertiesSet()} once the context is available.
   */
  private ApplicationContext applicationContext;

  private ProcessEngine processEngine;

  private SpringJobExecutor jobExecutor;

  private ThreadPoolTaskExecutor taskExecutor;

  /**
   * The adapter-owned datasource pool - <code>null</code> if the engine shares the
   * application's datasource.
   */
  private HikariDataSource ownDataSource;

  /**
   * The workflow modules whose processing was started and not yet stopped - the
   * reference count deciding when the engine-global job executor stops.
   */
  private final Set<String> startedWorkflowModules = new HashSet<>();

  private volatile boolean closed = false;

  /**
   * Prepares the engine holder for one adapter id (the engine itself is built in
   * {@link #afterPropertiesSet()}).
   *
   * @param adapterId The adapter id
   * @param properties The adapter id's engine settings
   *        (<code>vanillabp.adapters.&lt;id&gt;.*</code>)
   * @param applicationDataSource The application's datasource (unused - may be
   *        <code>null</code> - if <code>data-source.url</code> is configured)
   * @param applicationTransactionManager The application's transaction manager
   *        (unused - may be <code>null</code> - if <code>data-source.url</code> is
   *        configured)
   */
  public Camunda7EngineHolder(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final DataSource applicationDataSource,
      final PlatformTransactionManager applicationTransactionManager) {

    this.adapterId = adapterId;
    this.properties = properties;
    this.applicationDataSource = applicationDataSource;
    this.applicationTransactionManager = applicationTransactionManager;

  }

  @Override
  public void setApplicationContext(
      final ApplicationContext applicationContext) {

    this.applicationContext = applicationContext;

  }

  @Override
  public void afterPropertiesSet() {

    final DataSource dataSource;
    final PlatformTransactionManager transactionManager;
    if (properties.getDataSource().isConfigured()) {
      this.ownDataSource = buildOwnDataSource(adapterId, properties.getDataSource());
      dataSource = this.ownDataSource;
      transactionManager = new DataSourceTransactionManager(this.ownDataSource);
      log.info(
          "Camunda7[{}]: using the adapter's own datasource '{}' - the engine does not join the "
              + "application's transactions; starting workflows uses the two-phase pattern",
          adapterId,
          properties.getDataSource().getUrl());
    } else {
      dataSource = applicationDataSource;
      transactionManager = applicationTransactionManager;
    }

    // idiomatic job executor: SpringJobExecutor on a managed thread pool instead of
    // the engine-default DefaultJobExecutor (raw threads, activated at engine build)
    this.taskExecutor = new ThreadPoolTaskExecutor();
    this.taskExecutor.setThreadNamePrefix("vanillabp-camunda7-%s-jobs-".formatted(adapterId));
    this.taskExecutor.setCorePoolSize(3);
    this.taskExecutor.setMaxPoolSize(10);
    this.taskExecutor.setQueueCapacity(10);
    this.taskExecutor.initialize();
    this.jobExecutor = new SpringJobExecutor();
    this.jobExecutor.setTaskExecutor(this.taskExecutor);

    final var configuration = new SpringProcessEngineConfiguration();
    configuration.setApplicationContext(applicationContext);
    configuration.setProcessEngineName("vanillabp-camunda7-%s".formatted(adapterId));
    configuration.setDataSource(dataSource);
    configuration.setTransactionManager(transactionManager);
    configuration.setDatabaseSchemaUpdate(properties.getDatabaseSchemaUpdate());
    configuration.setJobExecutor(this.jobExecutor);
    // activation is deferred to startWorkflowProcessing (see class comment)
    configuration.setJobExecutorActivate(false);
    // Camunda 7.24 rejects deployments of processes without a history-time-to-live.
    // Provide an engine-wide default so BPMN models need not declare it individually
    // (a process may still override it via camunda:historyTimeToLive).
    configuration.setHistoryTimeToLive(properties.getHistoryTimeToLive());

    this.processEngine = configuration.buildProcessEngine();

  }

  private static HikariDataSource buildOwnDataSource(
      final String adapterId,
      final Camunda7EngineProperties.EngineDataSource dataSourceProperties) {

    final var config = new HikariConfig();
    config.setPoolName("vanillabp-camunda7-%s".formatted(adapterId));
    config.setJdbcUrl(dataSourceProperties.getUrl());
    if (dataSourceProperties.getUsername() != null) {
      config.setUsername(dataSourceProperties.getUsername());
    }
    if (dataSourceProperties.getPassword() != null) {
      config.setPassword(dataSourceProperties.getPassword());
    }
    if (dataSourceProperties.getDriverClassName() != null) {
      config.setDriverClassName(dataSourceProperties.getDriverClassName());
    }
    return new HikariDataSource(config);

  }

  public String getAdapterId() {

    return adapterId;

  }

  public ProcessEngine getProcessEngine() {

    return processEngine;

  }

  public RuntimeService getRuntimeService() {

    return processEngine.getRuntimeService();

  }

  public RepositoryService getRepositoryService() {

    return processEngine.getRepositoryService();

  }

  /**
   * @return Whether this adapter id's engine runs on its own datasource (see class
   *         comment)
   */
  public boolean usesSeparateDataSource() {

    return ownDataSource != null;

  }

  /**
   * Visible for tests asserting the deferred activation.
   *
   * @return Whether the job executor is currently active
   */
  public boolean isJobExecutorActive() {

    return (jobExecutor != null) && jobExecutor.isActive();

  }

  @Override
  public synchronized void startWorkflowProcessing(
      final String workflowModuleId) {

    startedWorkflowModules.add(workflowModuleId);
    if (!jobExecutor.isActive()) {
      log.info("Camunda7[{}]: starting the job executor", adapterId);
      jobExecutor.start();
    }

  }

  @Override
  public synchronized void stopWorkflowProcessing(
      final String workflowModuleId) {

    startedWorkflowModules.remove(workflowModuleId);
    if (startedWorkflowModules.isEmpty() && jobExecutor.isActive()) {
      log.info("Camunda7[{}]: stopping the job executor (last workflow module stopped)", adapterId);
      jobExecutor.shutdown();
    }

  }

  /**
   * Shutdown ordering: job executor stop &rarr; engine close &rarr; own datasource
   * close &rarr; thread pool shutdown. Safe to call more than once.
   */
  @Override
  public synchronized void close() {

    if (closed) {
      return;
    }
    closed = true;

    startedWorkflowModules.clear();
    if ((jobExecutor != null) && jobExecutor.isActive()) {
      jobExecutor.shutdown();
    }
    if (processEngine != null) {
      processEngine.close();
    }
    if (ownDataSource != null) {
      ownDataSource.close();
    }
    if (taskExecutor != null) {
      taskExecutor.shutdown();
    }
    log.info("Camunda7[{}]: engine closed", adapterId);

  }

}
