package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the EMBEDDED Camunda 7 engine gets to see of a workflow aggregate (stories
 * 28/28b), asserted against the real engine's {@code RuntimeService}:
 * <ul>
 * <li>an aggregate sharing ONE attribute ({@code @SyncWithBPMS}) writes exactly
 * that process variable - and nothing else, because since story 28b that one
 * annotation derives the class' mode (opt-in);</li>
 * <li>an aggregate carrying NO annotation at all writes NO variable: this adapter's
 * default is {@code AggregateSyncMode.NONE} (the engine reads the aggregate live -
 * shared values are operator context for the Cockpit, nothing else);</li>
 * <li>a BPMN gateway expression on a NOT shared attribute still works. This pins the
 * deliberate deviation of the Camunda 7 adapter: expressions are evaluated against
 * the LIVE aggregate through VanillaBP's EL resolver, so sharing is never a
 * precondition for a working model.</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: test contexts are cached and live in parallel - another
    // context's engine (and job executor) on the same H2 database would compete for
    // this test's jobs, and Hibernate's ddl-auto=create resets the aggregate tables
    // (restarting the ID sequences) while Camunda's tables survive
    "spring.datasource.url=jdbc:h2:mem:c7-sync-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7AggregateSyncIT {

  private static final String MODULE_ID = "c7-it";

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private org.camunda.bpm.engine.ProcessEngine processEngine;

  @Autowired
  private SyncTestRepository syncRepository;

  @Autowired
  private SyncTestWorkflowService syncWorkflowService;

  @Autowired
  private TaskTestRepository taskRepository;

  @Autowired
  private TaskTestWorkflowService taskWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private Map<String, Object> variablesOfWorkflow(
      final Object aggregateId) {

    final var processInstance = runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey("SyncProcess")
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult();
    assertNotNull(processInstance, "the workflow has to be active to inspect its variables");
    return runtimeService
        .createVariableInstanceQuery()
        .processInstanceIdIn(processInstance.getProcessInstanceId())
        .list()
        .stream()
        .collect(Collectors.toMap(
            variable -> variable.getName(),
            variable -> String.valueOf(variable.getValue())));

  }

  @Test
  @DisplayName("One @SyncWithBPMS attribute is written as operator context - and nothing else")
  public void sharedAttributeBecomesAProcessVariable() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new SyncTestAggregate();
      aggregate.setCustomerName("ACME");
      aggregate.setSecret("s3cr3t");
      // NOT shared, yet the gateway ${approved} has to evaluate it
      aggregate.setApproved(true);
      return syncWorkflowService.startSyncProcess(aggregate).getId();
    });

    // the gateway condition on the NOT shared attribute took the 'yes' path: the
    // embedded engine read the aggregate live (the deliberate C7 deviation)
    assertTrue(
        waitForTaskId(aggregateId),
        "the workflow has to reach the asynchronous task behind the gateway");

    assertEquals(
        Map.of("customerName", "ACME"),
        variablesOfWorkflow(aggregateId),
        "exactly the shared attribute - the class mode derived from it excludes everything else");

  }

  @Test
  @DisplayName("An aggregate without any annotation writes no process variable at all")
  public void unannotatedAggregateWritesNothing() {

    // started through VanillaBP (the sync point under test): TaskProcess runs to
    // its end, so the assertion is made against the ENGINE'S HISTORY - a variable
    // written at start would be recorded there
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      return taskWorkflowService.startTaskProcess(aggregate).getId();
    });

    assertTrue(
        waitFor(() -> taskRepository
            .findById(aggregateId)
            .map(TaskTestAggregate::getResults)
            .filter(results -> results.contains("approved"))
            .isPresent()),
        "TaskProcess has to pass the gateway reading the live aggregate");

    final var historyService = processEngine.getHistoryService();
    final var historicInstance = historyService
        .createHistoricProcessInstanceQuery()
        .processDefinitionKey("TaskProcess")
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    assertNotNull(historicInstance, "the finished workflow has to be in the history");
    assertEquals(
        java.util.List.of(),
        historyService
            .createHistoricVariableInstanceQuery()
            .processInstanceId(historicInstance.getId())
            .list()
            .stream()
            .map(variable -> variable.getName())
            .toList(),
        "the Camunda 7 default is NONE - the engine reads the aggregate live");

  }

  private boolean waitForTaskId(
      final Long aggregateId) {

    return waitFor(() -> syncRepository
        .findById(aggregateId)
        .map(SyncTestAggregate::getTaskId)
        .orElse(null) != null);

  }

  private boolean waitFor(
      final java.util.function.Supplier<Boolean> condition) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;

  }

}
