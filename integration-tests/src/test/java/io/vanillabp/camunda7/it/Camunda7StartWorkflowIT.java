package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Integration test of the Camunda 7 adapter against a <b>real embedded engine on H2</b>
 * (shared data source and transaction manager with the JPA aggregate persistence).
 * <p>
 * It proves the three properties of the C7 start:
 * <ol>
 *   <li>BPMN resources are deployed with the workflow module ID as the Camunda tenant ID,</li>
 *   <li>starting a workflow inside a transaction creates a process instance whose business
 *       key equals the aggregate ID, and</li>
 *   <li>rolling back the surrounding transaction removes BOTH the aggregate and the process
 *       instance (the embedded-engine guarantee).</li>
 * </ol>
 * The start is exercised both directly via the adapter's
 * {@link Camunda7ProcessService#startProcessInstance(String, String, Object)} and
 * end-to-end via the VanillaBP user API {@link ProcessService#startWorkflow(Object)}.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7StartWorkflowIT {

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @Autowired
  private RepositoryService repositoryService;

  @Autowired
  private RuntimeService runtimeService;

  @SuppressWarnings("rawtypes")
  @Autowired
  private Camunda7ProcessService camunda7ProcessService;

  @Autowired
  private AggregateRepository aggregateRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private ProcessService<TestAggregate> processService;

  @Test
  @DisplayName("BPMN resources are deployed with the workflow module ID as the Camunda tenant ID")
  public void deploymentExistsForModuleTenant() {

    final var count = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(BPMN_PROCESS_ID)
        .tenantIdIn(MODULE_ID)
        .count();

    assertEquals(1, count, "exactly one process definition deployed for tenant = module id");

  }

  @Test
  @DisplayName("Starting inside a transaction creates a process instance with business key = aggregate id")
  @SuppressWarnings("unchecked")
  public void startInsideTransactionCreatesInstanceWithBusinessKey() {

    final var aggregateId = transactionTemplate.execute(status -> {

      final var aggregate = new TestAggregate();
      aggregate.setContent("start-test");
      final var saved = aggregateRepository.save(aggregate);

      camunda7ProcessService.startProcessInstance(MODULE_ID, BPMN_PROCESS_ID, saved.getId());

      // inside the same transaction the instance exists with the aggregate id as business key
      final var instance = runtimeService
          .createProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(saved.getId()))
          .tenantIdIn(MODULE_ID)
          .singleResult();
      assertNotNull(instance, "process instance exists within the starting transaction");
      assertEquals(String.valueOf(saved.getId()), instance.getBusinessKey());

      return saved.getId();

    });

    assertNotNull(aggregateId);

  }

  @Test
  @DisplayName("Rolling back the transaction removes both the aggregate and the process instance")
  @SuppressWarnings("unchecked")
  public void rollbackRemovesAggregateAndProcessInstance() {

    final var aggregateIdHolder = new AtomicReference<Long>();

    final var exception = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {

          final var aggregate = new TestAggregate();
          aggregate.setContent("rollback-test");
          final var saved = aggregateRepository.save(aggregate);
          aggregateIdHolder.set(saved.getId());

          camunda7ProcessService.startProcessInstance(MODULE_ID, BPMN_PROCESS_ID, saved.getId());

          // the instance is visible inside the transaction before rolling back...
          assertEquals(
              1,
              runtimeService
                  .createProcessInstanceQuery()
                  .processInstanceBusinessKey(String.valueOf(saved.getId()))
                  .count());

          throw new RuntimeException("trigger rollback");

        }));
    assertEquals("trigger rollback", exception.getMessage());

    final var aggregateId = aggregateIdHolder.get();
    assertNotNull(aggregateId);

    // ...and after the rollback both the aggregate and the process instance are gone
    assertFalse(
        aggregateRepository.findById(aggregateId).isPresent(),
        "aggregate rolled back with the transaction");
    assertEquals(
        0,
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count(),
        "process instance rolled back with the transaction");

  }

  /**
   * The canonical end-to-end path through the VanillaBP user API: starting a workflow via
   * {@link ProcessService#startWorkflow(Object)} inside a transaction creates the process
   * instance (business key = aggregate id), and rolling the transaction back removes both
   * the aggregate and the process instance.
   */
  @Test
  @DisplayName("processService.startWorkflow joins the local transaction (created on commit, gone on rollback)")
  public void startWorkflowViaProcessServiceJoinsTransaction() {

    // committed start creates the instance with the aggregate id as business key
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("via-process-service");
      final var saved = processService.startWorkflow(aggregate);
      assertEquals(
          1,
          runtimeService
              .createProcessInstanceQuery()
              .processInstanceBusinessKey(String.valueOf(saved.getId()))
              .tenantIdIn(MODULE_ID)
              .count(),
          "process instance exists within the starting transaction");
      return saved.getId();
    });

    assertNotNull(aggregateId);

    // rolled-back start removes both the aggregate and the process instance
    final var rollbackIdHolder = new AtomicReference<Long>();
    final var exception = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setContent("via-process-service-rollback");
          final var saved = processService.startWorkflow(aggregate);
          rollbackIdHolder.set(saved.getId());
          throw new RuntimeException("trigger rollback");
        }));
    assertEquals("trigger rollback", exception.getMessage());

    final var rolledBackId = rollbackIdHolder.get();
    assertNotNull(rolledBackId);
    assertFalse(
        aggregateRepository.findById(rolledBackId).isPresent(),
        "aggregate rolled back with the transaction");
    assertEquals(
        0,
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(rolledBackId))
            .count(),
        "process instance rolled back with the transaction");

  }

}
