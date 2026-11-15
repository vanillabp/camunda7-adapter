package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two changes of the same workflow at the same moment, on a real embedded engine. Camunda 7
 * locks optimistically, so the one which writes second fails with an
 * {@code OptimisticLockingException}, and the engine rolls it back. The adapter calls that
 * a collision. So the core repeats the dispatch at once, and the outbox entry uses no
 * attempt for the race it lost.
 * <p>
 * The race is made, not waited for. A task listener on the completion of {@code UT_Task}
 * runs inside the command which completes the task, after the engine read the task and
 * before it writes anything. The first time, it lets a second transaction change the same
 * task and commit. Then the completion writes a task whose version is gone, and it fails
 * exactly the way a message correlation or a timer of the same workflow makes it fail.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7OptimisticLockingCollisionIT.RaceConfiguration.class
}, properties = {
    // a database of its own. Otherwise the phase-two outbox of a test class which Spring
    // keeps cached would dispatch the entries of the next one
    "spring.datasource.url=jdbc:h2:mem:c7-optimistic-locking-collision-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7OptimisticLockingCollisionIT {

  private static final String MODULE_ID = "c7-it";

  private static final String OUTBOX_TABLE = "VANILLABP_PHASE_TWO_OUTBOX";

  /**
   * Who changes the task in the transaction which wins the race.
   */
  private static final String THE_WINNER = "the-winner-of-the-race";

  @TestConfiguration
  static class RaceConfiguration {

    @Bean
    RacingTaskListener racingTaskListener() {

      return new RacingTaskListener();

    }

  }

  /**
   * Attaches a listener to the completion of {@code UT_Task}. While it is armed, the
   * listener lets another transaction change the task, once, and waits until that one
   * committed.
   */
  static class RacingTaskListener implements Camunda7EngineCustomizer {

    private final AtomicBoolean armed = new AtomicBoolean();

    /**
     * Every completion the engine started, also the one it rolled back.
     */
    private final List<String> completionsStarted = new CopyOnWriteArrayList<>();

    /**
     * What the transaction which should win the race threw. It is empty where the race was
     * made as planned.
     */
    private final List<RuntimeException> failuresOfTheWinner = new CopyOnWriteArrayList<>();

    @Override
    public List<BpmnParseListener> parseListenersAfter(
        final String adapterId) {

      return List.of(new AbstractBpmnParseListener() {

        @Override
        public void parseUserTask(
            final Element userTaskElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {

          if (!"UT_Task".equals(activity.getId())) {
            return;
          }
          ((UserTaskActivityBehavior) activity.getActivityBehavior())
              .getTaskDefinition()
              .addBuiltInTaskListener(TaskListener.EVENTNAME_COMPLETE, task -> {
                completionsStarted.add(task.getId());
                if (!armed.compareAndSet(true, false)) {
                  return;
                }
                // another thread, so the engine opens a transaction of its own for it and
                // commits it before this completion writes anything
                // the services are found through the engine context of this thread, so they
                // are taken here and not on the other one
                final var taskService = task
                    .getProcessEngineServices()
                    .getTaskService();
                final var taskId = task.getId();
                final var winner = new Thread(() -> {
                  try {
                    taskService.setAssignee(taskId, THE_WINNER);
                  } catch (final RuntimeException e) {
                    failuresOfTheWinner.add(e);
                  }
                });
                winner.start();
                try {
                  winner.join(10_000);
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });

        }

      });

    }

  }

  @Autowired
  private RacingTaskListener racingTaskListener;

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private ProcessEngine processEngine;

  @Autowired
  private TaskTestRepository repository;

  @Autowired
  private TaskTestWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private Long startUserTaskProcess() {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey("UserTaskProcess")
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });

  }

  private String openUserTask(
      final Long aggregateId) {

    final var task = processEngine
        .getTaskService()
        .createTaskQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .taskDefinitionKey("UT_Task")
        .singleResult();
    return task == null
        ? null
        : task.getId();

  }

  private static void awaitUntil(
      final BooleanSupplier condition,
      final String what) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 20_000;
    while (!condition.getAsBoolean()) {
      assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for %s".formatted(what));
      Thread.sleep(50);
    }

  }

  private List<java.util.Map<String, Object>> completionsOf(
      final Long aggregateId) {

    return jdbcTemplate
        .queryForList(
            "SELECT STATUS, ATTEMPTS FROM %s WHERE AGGREGATE_ID = ? AND OPERATION = 'COMPLETE_USER_TASK'"
                .formatted(OUTBOX_TABLE),
            String.valueOf(aggregateId));

  }

  @Test
  @DisplayName("A completion which loses the optimistic lock is repeated at once and uses no attempt")
  public void aLostOptimisticLockIsRepeatedAtOnce() throws Exception {

    final var aggregateId = startUserTaskProcess();
    awaitUntil(() -> openUserTask(aggregateId) != null, "the user task");
    final var taskId = openUserTask(aggregateId);

    racingTaskListener.armed.set(true);
    transactionTemplate.executeWithoutResult(status -> workflowService
        .completeUserTask(repository.findById(aggregateId).orElseThrow(), taskId));

    awaitUntil(
        () -> completionsOf(aggregateId)
            .stream()
            .anyMatch(row -> !"OPEN".equals(row.get("STATUS"))),
        "the completion to be dispatched");

    assertEquals(List.of(), racingTaskListener.failuresOfTheWinner, "the race could not be made");
    // the race happened: the first completion was rolled back, the repetition went through,
    // and the change of the winner stayed
    assertEquals(List.of(taskId, taskId), racingTaskListener.completionsStarted);
    assertEquals(
        THE_WINNER,
        processEngine
            .getHistoryService()
            .createHistoricTaskInstanceQuery()
            .taskId(taskId)
            .singleResult()
            .getAssignee());
    assertEquals(null, openUserTask(aggregateId), "the user task is still open");

    // the store counts the attempt which went through, and nothing for the lost race
    final var completion = completionsOf(aggregateId).get(0);
    assertEquals("DONE", completion.get("STATUS"));
    assertEquals(
        1,
        ((Number) completion.get("ATTEMPTS")).intValue(),
        "the lost race was counted as a failed attempt, so the entry waited for its backoff");

  }

}
