package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of pushing a changed aggregate (story 44) against a real embedded
 * Camunda 7 engine. Two things are worth an engine: a conditional event only ever
 * looks at its condition when a variable of its scope changes - so the push is what
 * makes it fire - and a task-scoped push has to stay inside that one instance of a
 * multi-instance activity.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-aggregate-changed-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7AggregateChangedIT {

  @Autowired
  private AggregateChangedTestWorkflowService workflowService;

  @Autowired
  private AggregateChangedTestRepository repository;

  @Autowired
  private MultiInstancePushTestWorkflowService multiInstanceWorkflowService;

  @Autowired
  private MultiInstancePushTestRepository multiInstanceRepository;

  @Autowired
  private RuntimeService runtimeService;

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

  private String processInstanceIdOf(
      final Object aggregateId) {

    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    return instance == null
        ? null
        : instance.getId();

  }

  @Test
  @DisplayName("a conditional event fires once the changed aggregate was pushed")
  public void aConditionalEventWaitsForThePush() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> runtimeService
            .createEventSubscriptionQuery()
            .processInstanceId(processInstanceIdOf(aggregateId))
            .eventType("conditional")
            .count() > 0,
        "the workflow to wait at the conditional event");

    // the attribute the condition reads is set, but nothing tells the engine yet
    Thread.sleep(500);
    assertEquals(
        null,
        repository.findById(aggregateId).orElseThrow().getProcessedBy(),
        "the workflow may not continue before the aggregate was pushed");

    transactionTemplate.executeWithoutResult(status -> workflowService.becomeReady(aggregateId));

    awaitUntil(
        () -> "conditionMet".equals(
            repository
                .findById(aggregateId)
                .map(AggregateChangedTestAggregate::getProcessedBy)
                .orElse(null)),
        "the task behind the conditional event to run");

  }

  @Test
  @DisplayName("a task-scoped push stays in that instance of a multi-instance activity")
  public void aTaskScopedPushDoesNotDisturbTheSiblings() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> multiInstanceWorkflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> multiInstanceRepository
            .findById(aggregateId)
            .map(MultiInstancePushTestAggregate::getTaskIds)
            .filter(taskIds -> taskIds.split(",").length == 2)
            .isPresent(),
        "both instances of the multi-instance activity to park");

    final var taskIds = multiInstanceRepository.findById(aggregateId).orElseThrow().getTaskIds().split(",");
    final var pushed = taskIds[0];
    final var sibling = taskIds[1];

    transactionTemplate
        .executeWithoutResult(status -> multiInstanceWorkflowService.pushInto(aggregateId, pushed));

    final var marker = Camunda7ProcessService.AGGREGATE_CHANGED_MARKER;
    assertTrue(
        runtimeService.getVariablesLocal(pushed).containsKey(marker),
        "the push has to land in the scope of the task it named");
    assertFalse(
        runtimeService.getVariablesLocal(sibling).containsKey(marker),
        "a sibling instance may never see what another instance pushed");
    assertFalse(
        runtimeService.getVariablesLocal(processInstanceIdOf(aggregateId)).containsKey(marker),
        "a task-scoped push deliberately leaves the workflow's global scope as it was");

    // and the global push does what the other overload promises
    transactionTemplate.executeWithoutResult(status -> multiInstanceWorkflowService.pushGlobally(aggregateId));

    assertTrue(
        runtimeService.getVariablesLocal(processInstanceIdOf(aggregateId)).containsKey(marker),
        "the global push has to land at the workflow's scope");

  }

}
