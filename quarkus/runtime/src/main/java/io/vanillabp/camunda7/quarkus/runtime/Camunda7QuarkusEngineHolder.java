package io.vanillabp.camunda7.quarkus.runtime;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agroal.api.AgroalDataSource;
import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.engine.Camunda7JobExecutorLifecycle;
import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskExpressionManager;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import jakarta.transaction.TransactionManager;

/**
 * Owns ONE embedded Camunda 7 engine per configured adapter id on Quarkus (engine
 * name <code>vanillabp-camunda7-&lt;id&gt;</code>) - the Quarkus counterpart of the
 * Spring Boot module's engine holder, wired per the analysis probe's proven recipe:
 * <ul>
 *   <li>{@link Camunda7QuarkusProcessEngineConfiguration} on an Agroal datasource
 *       (the application's default, or the NAMED datasource configured via
 *       <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>) with the CDI
 *       Narayana {@link TransactionManager};</li>
 *   <li>the engine classloader is pinned to the Quarkus runtime TCCL - without it
 *       the job executor's threads fail with {@code ClassNotFoundException} on
 *       delegate classes (the engine's own thread pool is kept: proven by the
 *       probe, and the C7 family is JVM-mode only anyway; a context-propagating
 *       {@code ManagedExecutor} variant is a possible later refinement);</li>
 *   <li>job-executor activation is DEFERRED to the deployment pipeline's
 *       {@code startWorkflowProcessing} (the shared reference-counted
 *       {@link Camunda7JobExecutorLifecycle} - the same semantics as on Spring
 *       Boot).</li>
 * </ul>
 */
public class Camunda7QuarkusEngineHolder implements Camunda7WorkflowProcessingLifecycle, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Camunda7QuarkusEngineHolder.class);

  private final String adapterId;

  /**
   * The task connectables of this engine, registered by the deployment service
   * during wireBpmn and looked up by the engine's EL resolver.
   */
  private final Camunda7TaskRegistry taskRegistry = new Camunda7TaskRegistry();

  private final boolean usesSeparateDataSource;

  private final ProcessEngine processEngine;

  private final Camunda7JobExecutorLifecycle jobExecutorLifecycle;

  private volatile boolean closed = false;

  /**
   * Builds the engine for one adapter id.
   *
   * @param adapterId The adapter id
   * @param properties The adapter id's engine settings
   *        (<code>vanillabp.adapters.&lt;id&gt;.*</code>)
   * @param dataSource The Agroal datasource the engine runs on
   * @param usesSeparateDataSource Whether the datasource is a NAMED one (not the
   *        application's default) - such ids start workflows two-phase (see the
   *        process service)
   * @param transactionManager The CDI (Narayana) transaction manager
   */
  public Camunda7QuarkusEngineHolder(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final AgroalDataSource dataSource,
      final boolean usesSeparateDataSource,
      final TransactionManager transactionManager,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this.adapterId = adapterId;
    this.usesSeparateDataSource = usesSeparateDataSource;

    final var configuration = new Camunda7QuarkusProcessEngineConfiguration(transactionManager);
    // VanillaBP task wiring: top-level EL names resolve @WorkflowTask methods and
    // workflow-aggregate attributes; the parse listener aligns transaction
    // boundaries with remote BPMS (async before/after)
    configuration.setExpressionManager(new Camunda7TaskExpressionManager(taskRegistry, workflowTaskInvoker));
    configuration.setCustomPreBPMNParseListeners(new java.util.ArrayList<>(
        java.util.List.of(new Camunda7AsyncBpmnParseListener(
            new io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener(
                workflowTaskInvoker, taskRegistry), new io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener(
                    workflowTaskInvoker, taskRegistry)))));
    configuration.setProcessEngineName("vanillabp-camunda7-%s".formatted(adapterId));
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate(properties.getDatabaseSchemaUpdate());
    // activation is deferred to startWorkflowProcessing (26e semantics)
    configuration.setJobExecutorActivate(false);
    // job-executor threads must load delegate classes via the Quarkus runtime
    // classloader (proven pitfall: ClassNotFoundException otherwise)
    configuration.setClassLoader(Thread.currentThread().getContextClassLoader());
    // Camunda 7.24 rejects deployments of processes without a history-time-to-live.
    // Provide an engine-wide default so BPMN models need not declare it individually
    // (a process may still override it via camunda:historyTimeToLive).
    configuration.setHistoryTimeToLive(properties.getHistoryTimeToLive());
    // an own table prefix makes two adapter ids distinct engines on ONE datasource
    // (the side-by-side migration setup on a single database, story 34)
    if ((properties.getTablePrefix() != null) && !properties.getTablePrefix().isBlank()) {
      configuration.setDatabaseTablePrefix(properties.getTablePrefix());
    }

    this.processEngine = configuration.buildProcessEngine();
    this.jobExecutorLifecycle = new Camunda7JobExecutorLifecycle(
        adapterId, ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration()).getJobExecutor());

  }

  public Camunda7TaskRegistry getTaskRegistry() {

    return taskRegistry;

  }

  public String getAdapterId() {

    return adapterId;

  }

  public ProcessEngine getProcessEngine() {

    return processEngine;

  }

  public org.camunda.bpm.engine.TaskService getTaskService() {

    return processEngine.getTaskService();

  }

  public RuntimeService getRuntimeService() {

    return processEngine.getRuntimeService();

  }

  public RepositoryService getRepositoryService() {

    return processEngine.getRepositoryService();

  }

  public org.camunda.bpm.engine.HistoryService getHistoryService() {

    return processEngine.getHistoryService();

  }

  /**
   * @return Whether this adapter id's engine runs on a NAMED datasource (see class
   *         comment)
   */
  public boolean usesSeparateDataSource() {

    return usesSeparateDataSource;

  }

  /**
   * Visible for tests asserting the deferred activation.
   *
   * @return Whether the job executor is currently active
   */
  public boolean isJobExecutorActive() {

    return jobExecutorLifecycle.isActive();

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId) {

    jobExecutorLifecycle.startWorkflowProcessing(workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId) {

    jobExecutorLifecycle.stopWorkflowProcessing(workflowModuleId);

  }

  /**
   * Shutdown ordering: job executor stop &rarr; engine close (the Agroal datasource
   * itself is owned and closed by Quarkus). Safe to call more than once.
   */
  @Override
  public synchronized void close() {

    if (closed) {
      return;
    }
    closed = true;

    jobExecutorLifecycle.shutdown();
    processEngine.close();
    log.info("Camunda7[{}]: engine closed", adapterId);

  }

}
