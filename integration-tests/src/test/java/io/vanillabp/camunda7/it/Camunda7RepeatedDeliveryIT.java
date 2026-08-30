package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an engine datasource of its own changes about the INBOUND direction, asserted
 * against both modes side by side ({@code c7} shares the application's datasource,
 * {@code c7b} runs on a named one):
 * <ul>
 * <li>the adapter says whether its deliveries may repeat, per mode;</li>
 * <li>on the own datasource the handler's transaction commits before the engine's job
 * does, so a job which fails afterwards is handed out again - and the repeated delivery
 * is answered from the record instead of running the {@code @WorkflowTask} method
 * again;</li>
 * <li>on the shared datasource the same failure rolls the handler's work back with the
 * job, nothing is recorded, and the handler runs again - which is what a redelivery
 * means there.</li>
 * </ul>
 * The job is failed by an end listener of the task
 * ({@link FailTheJobOnce}), which is the only moment where the handler is done and the
 * engine's transaction is not.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7RepeatedDeliveryIT.NamedDataSourceConfiguration.class
}, properties = {
    // a database of its own: test contexts are cached and live in parallel, and an
    // engine of another context would poll this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-repeated-delivery-it;DB_CLOSE_DELAY=-1", "vanillabp.prioritized-adapters=c7,c7b", "vanillabp.adapters.c7b.type=camunda7", "vanillabp.adapters.c7b.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7b.data-source-name=c7bDataSource", "vanillabp.workflow-modules.c7-it.adapters.c7b.resources-location=classpath*:c7-it/processes"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7RepeatedDeliveryIT {

  /**
   * The application-provided datasource bean the {@code c7b} engine runs on.
   * {@code defaultCandidate = false} keeps it out of by-type injection and lets Spring
   * Boot's default-datasource auto-configuration stay active (the standard pattern for
   * additional application datasources).
   */
  @org.springframework.boot.test.context.TestConfiguration
  public static class NamedDataSourceConfiguration {

    @org.springframework.context.annotation.Bean(defaultCandidate = false)
    public javax.sql.DataSource c7bDataSource() {

      return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7b-repeated-delivery;DB_CLOSE_DELAY=-1");

    }

  }

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "RepeatedDeliveryProcess";

  @Autowired
  @Qualifier("Camunda7_Engine_c7")
  private Camunda7EngineHolder sharedDataSourceEngine;

  @Autowired
  @Qualifier("Camunda7_Engine_c7b")
  private Camunda7EngineHolder separateDataSourceEngine;

  @Autowired
  @Qualifier("Camunda7_ProcessService_c7")
  private Camunda7ProcessService<?> sharedDataSourceProcessService;

  @Autowired
  @Qualifier("Camunda7_ProcessService_c7b")
  private Camunda7ProcessService<?> separateDataSourceProcessService;

  @Autowired
  private RepeatedDeliveryTestRepository repository;

  @Autowired
  private RepeatedDeliveryTestWorkflowService workflowService;

  @Autowired
  private RepeatedDeliveryProbe probe;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private javax.sql.DataSource applicationDataSource;

  @AfterEach
  public void forgetWhatThisTestSteered() {

    probe.reset();

  }

  @Test
  @DisplayName("Only the adapter id on its own datasource says that a delivery may repeat")
  public void deliveryRepetitionIsAnsweredFromTheDatasourceMode() {

    assertFalse(
        sharedDataSourceProcessService.deliversTasksAtLeastOnce(),
        "sharing the application's datasource means delivering in its transaction");
    assertTrue(
        separateDataSourceProcessService.deliversTasksAtLeastOnce(),
        "an own datasource means the aggregate commits before the engine does");

  }

  @Test
  @DisplayName("On an own datasource the repeated delivery is answered from the record")
  public void repeatedDeliveryOnAnOwnDataSourceIsAnsweredFromTheRecord() {

    final var aggregateId = transactionTemplate
        .execute(status -> repository.save(new RepeatedDeliveryTestAggregate()).getId());

    probe.failTheNextJob();
    separateDataSourceEngine
        .getRuntimeService()
        .createProcessInstanceByKey(BPMN_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();

    AwaitPhaseTwo
        .until(
            () -> workflowEnded(separateDataSourceEngine, aggregateId),
            "the workflow to end after the repeated delivery");

    assertEquals(
        1,
        probe.handlerInvocations(),
        "the repeated delivery must be answered from the record, not by the method");
    assertEquals(
        1,
        committedHandlerRuns(aggregateId),
        "the handler's work committed in its own transaction and survived the failed job");
    assertEquals(1, recordedDeliveriesOf("c7b"), "the delivery of the own-datasource engine is remembered");

  }

  @Test
  @DisplayName("On the shared datasource nothing is recorded and the handler runs again")
  public void repeatedDeliveryOnTheSharedDataSourceRunsTheHandlerAgain() {

    final var aggregateId = transactionTemplate.execute(status -> {
      probe.failTheNextJob();
      return workflowService
          .startOnTheFirstPrioritizedAdapter(new RepeatedDeliveryTestAggregate())
          .getId();
    });

    AwaitPhaseTwo
        .until(
            () -> workflowEnded(sharedDataSourceEngine, aggregateId),
            "the workflow to end after the retried job");

    assertEquals(
        2,
        probe.handlerInvocations(),
        "the failed job took the handler's work with it, so the engine's retry runs the method again");
    assertEquals(
        1,
        committedHandlerRuns(aggregateId),
        "only the run which committed is in the aggregate");
    assertEquals(
        0,
        recordedDeliveriesOf("c7"),
        "an engine delivering in the application's transaction records nothing");

  }

  /**
   * @param aggregateId The workflow aggregate's ID
   * @return How many handler runs the aggregate carries, i.e. how many of them committed
   */
  private int committedHandlerRuns(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).orElseThrow().getHandlerRuns());

  }

  /**
   * Waiting for the workflow to END rather than for the handler's work to appear: the
   * failing job is what makes this scenario, and its retry has to have happened before
   * anything is counted.
   *
   * @param engine The engine the workflow runs in
   * @param aggregateId The workflow aggregate's ID
   * @return Whether that workflow has ended
   */
  private boolean workflowEnded(
      final Camunda7EngineHolder engine,
      final Long aggregateId) {

    return engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .finished()
        .count() == 1;

  }

  /**
   * Counted per adapter id, because both modes run the same BPMN process here and the
   * records of the two share one table.
   *
   * @param adapterId The adapter id which delivered
   * @return How many deliveries of this test's BPMN process VanillaBP has written down
   */
  private int recordedDeliveriesOf(
      final String adapterId) {

    return new org.springframework.jdbc.core.JdbcTemplate(applicationDataSource)
        .queryForObject(
            "SELECT COUNT(*) FROM VANILLABP_TASK_DELIVERY WHERE BPMN_PROCESS_ID = ? AND ADAPTER_ID = ?",
            Integer.class,
            BPMN_PROCESS_ID,
            adapterId);

  }

}
