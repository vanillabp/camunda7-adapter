package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.EventSubscriptionQuery;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ExecutionQuery;
import org.camunda.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Camunda 7 progresses the workflow after the commit, so phase one only
 * ASKS - and an embedded engine answers from the caller's own transaction, exactly and
 * for free. What used to fail synchronously (a task which is gone, a message nobody
 * waits for) therefore still fails synchronously instead of turning into a log line
 * behind the commit.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7PhaseOneChecksTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static AggregatePersistenceAware<String> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<String> getAggregateClass() {
        return String.class;
      }

      @Override
      public Object getAggregateId(
          final String aggregate) {
        return aggregate;
      }

    };

  }

  /**
   * @param executions What an execution query counts (the task's parked execution
   *        respectively an execution waiting for a message)
   * @param userTasks What a task query counts
   * @param messageStartEvents What an event-subscription query counts
   */
  private static Camunda7ProcessService<String> processService(
      final long executions,
      final long userTasks,
      final long messageStartEvents) {

    return processService(executions, userTasks, messageStartEvents, null);

  }

  /**
   * @param executions What an execution query counts (the task's parked execution
   *        respectively an execution waiting for a message)
   * @param userTasks What a task query counts
   * @param messageStartEvents What an event-subscription query counts
   * @param expectedCorrelationId What the waiting executions hold in their local
   *        correlation-id variable
   */
  private static Camunda7ProcessService<String> processService(
      final long executions,
      final long userTasks,
      final long messageStartEvents,
      final String expectedCorrelationId) {

    // built BEFORE stubbing the query: mocking within a 'when' is unfinished stubbing
    final var waitingExecutions = new ArrayList<Execution>();
    for (var index = 0; index < executions; index++) {
      final var execution = Mockito.mock(Execution.class);
      Mockito.when(execution.getId()).thenReturn("execution-"
          + index);
      waitingExecutions.add(execution);
    }

    final var executionQuery = Mockito.mock(ExecutionQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(executionQuery.count()).thenReturn(executions);
    Mockito.when(executionQuery.list()).thenReturn(waitingExecutions);
    final var eventSubscriptionQuery = Mockito.mock(EventSubscriptionQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(eventSubscriptionQuery.count()).thenReturn(messageStartEvents);
    final var runtimeService = Mockito.mock(RuntimeService.class);
    Mockito.when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
    Mockito.when(runtimeService.createEventSubscriptionQuery()).thenReturn(eventSubscriptionQuery);
    Mockito
        .when(runtimeService.getVariableLocal(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(expectedCorrelationId);

    final var taskQuery = Mockito.mock(TaskQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(taskQuery.count()).thenReturn(userTasks);
    final var taskService = Mockito.mock(TaskService.class);
    Mockito.when(taskService.createTaskQuery()).thenReturn(taskQuery);

    return new Camunda7ProcessService<>("camunda7", runtimeService, taskService, null, null);

  }

  @Test
  @DisplayName("Completing or canceling a task which is gone fails where the application called it")
  public void goneTaskFailsInPhaseOne() {

    final var testee = processService(0, 0, 0);

    final var completing = assertThrows(
        IllegalStateException.class,
        () -> testee.completeTaskPhaseOne(MODULE, PROCESS, persistence(), "4711", "task-1"));
    assertTrue(completing.getMessage().contains("task-1"), completing.getMessage());
    assertTrue(completing.getMessage().contains("completing"), completing.getMessage());

    assertThrows(
        IllegalStateException.class,
        () -> testee.cancelTaskPhaseOne(MODULE, PROCESS, persistence(), "4711", "task-1", "Denied"));
    assertThrows(
        IllegalStateException.class,
        () -> testee.completeUserTaskPhaseOne(MODULE, PROCESS, persistence(), "4711", "user-task-1"));
    assertThrows(
        IllegalStateException.class,
        () -> testee.cancelUserTaskPhaseOne(MODULE, PROCESS, persistence(), "4711", "user-task-1", "Denied"));

  }

  @Test
  @DisplayName("A task which is still there passes phase one without advancing anything")
  public void existingTaskPassesPhaseOne() {

    final var testee = processService(1, 1, 0);

    assertDoesNotThrow(() -> testee.completeTaskPhaseOne(MODULE, PROCESS, persistence(), "4711", "task-1"));
    assertDoesNotThrow(() -> testee.completeUserTaskPhaseOne(MODULE, PROCESS, persistence(), "4711", "user-task-1"));

  }

  @Test
  @DisplayName("Correlating a message nobody waits for fails where the application called it")
  public void messageWithoutSubscriptionFailsInPhaseOne() {

    final var testee = processService(0, 0, 0);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> testee.correlateMessagePhaseOne(MODULE, PROCESS, persistence(), "4711", "LoanApproved", null));
    assertTrue(failure.getMessage().contains("LoanApproved"), failure.getMessage());
    assertTrue(failure.getMessage().contains("4711"), failure.getMessage());

    // with a waiting subscription the call passes - correlating itself happens after
    // the commit
    assertDoesNotThrow(
        () -> processService(1, 0, 0)
            .correlateMessagePhaseOne(MODULE, PROCESS, persistence(), "4711", "LoanApproved", null));

  }

  @Test
  @DisplayName("A correlation id nobody waits for fails where the application called it")
  public void mismatchingCorrelationIdFailsInPhaseOne() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> processService(1, 0, 0, "payment-42")
            .correlateMessagePhaseOne(MODULE, PROCESS, persistence(), "4711", "PaymentReceived", "wrong-id"));
    assertTrue(failure.getMessage().contains("wrong-id"), failure.getMessage());

    // the counter-check: the id the waiting execution expects passes
    assertDoesNotThrow(
        () -> processService(1, 0, 0, "payment-42")
            .correlateMessagePhaseOne(MODULE, PROCESS, persistence(), "4711", "PaymentReceived", "payment-42"));

  }

  @Test
  @DisplayName("Starting by a message no start event listens to fails where the application called it")
  public void unknownMessageStartEventFailsInPhaseOne() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> processService(0, 0, 0)
            .startWorkflowByMessagePhaseOne(MODULE, PROCESS, persistence(), "4711", "LoanRequested"));
    assertTrue(failure.getMessage().contains("LoanRequested"), failure.getMessage());

    assertDoesNotThrow(
        () -> processService(0, 0, 1)
            .startWorkflowByMessagePhaseOne(MODULE, PROCESS, persistence(), "4711", "LoanRequested"));

  }

  @Test
  @DisplayName("Starting a workflow has nothing to ask - it never fails in phase one")
  public void startingAWorkflowPassesPhaseOne() {

    assertDoesNotThrow(
        () -> processService(0, 0, 0).startWorkflowPhaseOne(MODULE, PROCESS, persistence(), "4711"));

  }

}
