package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of the name-clash-avoidance mode {@code use-prefix} on a real
 * embedded Camunda 7 engine (story 35): the workflow module is NOT deployed into a
 * tenant, instead every identifier the engine resolves across process definitions
 * carries the workflow module id as prefix. What has to keep working:
 * <ul>
 * <li>deployment without a tenant, under the prefixed process definition keys;</li>
 * <li>starting a workflow through the {@code ProcessService} with the PLAIN process id
 * - the business code never sees a prefix;</li>
 * <li>a gateway condition on an aggregate attribute: VanillaBP's EL resolver has to
 * find the aggregate although the engine reports only the prefixed definition key and
 * no tenant (the workflow module is resolved from the registered key);</li>
 * <li>task delivery: the prefixed key has to be translated back to find the
 * {@code @WorkflowTask} handler;</li>
 * <li>message correlation with the PLAIN message name against the prefixed
 * subscription.</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-prefix-it;DB_CLOSE_DELAY=-1", "vanillabp.adapters.c7.name-clash-avoidance=use-prefix"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7NameClashAvoidanceIT {

  private static final String MODULE_ID = "c7-it";

  private static final String PREFIX = MODULE_ID
      + "__";

  @Autowired
  private RepositoryService repositoryService;

  @Autowired
  private RuntimeService runtimeService;

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

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

  @Test
  @DisplayName("the workflow module is deployed under prefixed keys and WITHOUT a tenant")
  public void deploymentIsPrefixedAndTenantFree() {

    final var definition = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(PREFIX
            + "SyncProcess")
        .withoutTenantId()
        .latestVersion()
        .singleResult();

    assertNotNull(definition, "the process has to be deployed under its prefixed key");
    assertNull(definition.getTenantId(), "prefixing replaces the tenant - that is the point of the mode");

    assertNull(
        repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("SyncProcess")
            .singleResult(),
        "the plain key must not be deployed - the engine only knows prefixed ids");

  }

  @Test
  @DisplayName("start, gateway on the live aggregate and task delivery work without a tenant")
  public void workflowRunsWithPrefixedIdentifiers() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new SyncTestAggregate();
      aggregate.setCustomerName("ACME");
      // NOT shared with the BPMS, yet the gateway '${approved}' has to evaluate it
      aggregate.setApproved(true);
      // the business code passes no prefix anywhere - it does not know about one
      return syncWorkflowService.startSyncProcess(aggregate).getId();
    });

    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .withoutTenantId()
        .singleResult();
    assertNotNull(instance, "the workflow has to be started without a tenant");
    assertEquals(
        PREFIX
            + "SyncProcess",
        repositoryService
            .getProcessDefinition(instance.getProcessDefinitionId())
            .getKey(),
        "the ProcessService has to address the prefixed definition");

    // reaching the asynchronous task proves BOTH: the gateway condition was evaluated
    // against the live aggregate (EL resolver) and the delivered task was routed back
    // to the @WorkflowTask handler although the engine reported the prefixed key
    awaitUntil(
        () -> syncRepository
            .findById(aggregateId)
            .map(SyncTestAggregate::getTaskId)
            .orElse(null) != null,
        "the workflow to reach the asynchronous task behind the gateway");

  }

  @Test
  @DisplayName("correlateMessage takes the PLAIN message name and finds the prefixed subscription")
  public void correlationIsTransparent() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      final var saved = taskRepository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey(PREFIX
              + "MessageProcess")
          .processDefinitionWithoutTenantId()
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });

    awaitUntil(
        () -> runtimeService
            .createExecutionQuery()
            .messageEventSubscriptionName(PREFIX
                + "PaymentReceived")
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count() > 0,
        "the instance to wait at the prefixed message subscription");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = taskRepository.findById(aggregateId).orElseThrow();
      // the plain name, as written in the BPMN file and in the business code
      taskWorkflowService.correlate(aggregate, "PaymentReceived");
    });

    awaitUntil(
        () -> runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count() == 0,
        "MessageProcess to end after the correlation");
    assertTrue(
        taskRepository
            .findById(aggregateId)
            .orElseThrow()
            .getResults()
            .contains("message-arrived"),
        "the @WorkflowTask behind the message catch event has to have run");

  }

}
