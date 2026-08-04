package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of {@code @WorkflowTask} processing on a real embedded Camunda 7
 * engine (H2, shared datasource/transaction manager with the JPA aggregate
 * persistence) - story 21b:
 * <ul>
 * <li>happy path: service task executes the handler, aggregate mutation persisted,
 * task completed, process ends - incl. a gateway condition reading an aggregate
 * attribute through VanillaBP's EL resolver;</li>
 * <li>{@code TaskException}: error-boundary routing WITH committed aggregate
 * changes (the V1 contract);</li>
 * <li>technical exception: job transaction rolled back (aggregate unchanged), job
 * retried (retry counter decremented);</li>
 * <li>{@code @TaskId}: the task stays open, the job executor does not redeliver;</li>
 * <li>multi-instance: collection from an aggregate attribute, index/total/element
 * bound;</li>
 * <li>async-before/after forced onto service tasks by the parse listener (asserted
 * on the parsed process definition).</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TaskProcessingIT {

  private static final String MODULE_ID = "c7-it";

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
  private org.springframework.context.ApplicationContext applicationContext;

  private Long startWorkflow() {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      return workflowService.startTaskProcess(aggregate).getId();
    });

  }

  private Long startSecondaryProcess(
      final String bpmnProcessId,
      final boolean approved,
      final List<String> items) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(approved);
      aggregate.setItems(items);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey(bpmnProcessId)
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });

  }

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

  private String instanceIdOf(
      final Long aggregateId) {

    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    return instance != null
        ? instance.getId()
        : null;

  }

  private boolean processEnded(
      final Long aggregateId) {

    return runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .count() == 0;

  }

  @Test
  @DisplayName("Happy path: handler executes, aggregate persisted, gateway reads aggregate attribute, process ends")
  public void happyPathWithGatewayCondition() throws Exception {

    final var approvedId = startWorkflow();
    awaitUntil(() -> processEnded(approvedId), "approved TaskProcess to end");
    assertEquals("happy|approved", repository.findById(approvedId).orElseThrow().getResults());

    // approved=false: the gateway's default flow skips 'afterApproval'
    final var rejectedId = startSecondaryProcess("TaskProcess", false, null);
    awaitUntil(() -> processEnded(rejectedId), "rejected TaskProcess to end");
    assertEquals("happy", repository.findById(rejectedId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("TaskException routes through the error boundary and COMMITS the aggregate changes")
  public void taskExceptionRoutesErrorBoundaryAndCommits() throws Exception {

    final var aggregateId = startSecondaryProcess("ErrorProcess", true, null);
    awaitUntil(() -> processEnded(aggregateId), "ErrorProcess to end via the error boundary");

    // both the mutation of the THROWING handler and the boundary path are visible
    assertEquals("error-raised|handled", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("A technical exception rolls back the job transaction and decrements the job retries")
  public void technicalExceptionRollsBackAndRetries() throws Exception {

    final var aggregateId = startSecondaryProcess("FailProcess", true, null);

    final var instanceId = instanceIdOf(aggregateId);

    // the job executor retries the failing job - wait for a decremented retry count
    awaitUntil(
        () -> processEngine.getManagementService()
            .createJobQuery()
            .processInstanceId(instanceId)
            .list()
            .stream()
            .anyMatch(job -> job.getRetries() < 3),
        "the failing job's retries to be decremented");

    final var job = processEngine.getManagementService()
        .createJobQuery()
        .processInstanceId(instanceId)
        .singleResult();
    assertNotNull(job, "the failing job stays (task not completed)");
    assertTrue(job.getRetries() < 3, "retries decremented");

    // the handler's mutation was rolled back with the job transaction
    assertNull(repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("@TaskId: the task stays open, its aggregate changes commit, the job executor does not redeliver")
  public void asyncTaskStaysOpen() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);

    // the handler ran (aggregate committed) ...
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");

    // ... but the task stays open: instance alive, execution parked at the task
    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    assertNotNull(instance, "AsyncProcess stays active");
    final var activeActivities = runtimeService.getActiveActivityIds(instance.getId());
    assertEquals(List.of("AP_Task"), activeActivities, "execution parked at the async task");

    // no job remains - the job executor does NOT redeliver an open async task
    // (give a potential redelivery a moment to appear before asserting)
    Thread.sleep(500);
    assertEquals(
        0,
        processEngine.getManagementService()
            .createJobQuery()
            .processInstanceId(instance.getId())
            .count(),
        "no job pending for the parked async task");
    assertEquals("async-open", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("completeTask resumes the parked async task and the process ends")
  public void completeTaskResumesProcess() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("completing");
      workflowService.completeAsyncTask(aggregate, aggregate.getTaskId());
    });

    // the signal leaves the activity; async-after parks a job - the process ends
    // through the job executor
    awaitUntil(() -> processEnded(aggregateId), "AsyncProcess to end after completeTask");
    assertEquals("async-open|completing", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("cancelTask propagates the BPMN error through the error boundary")
  public void cancelTaskRoutesErrorBoundary() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncCancelProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.cancelAsyncTask(aggregate, aggregate.getTaskId(), "PAYMENT_FAILED");
    });

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("cancel-handled");
        },
        "the error boundary to route to the handling task");
    awaitUntil(() -> processEnded(aggregateId), "AsyncCancelProcess to end via the boundary");

  }

  @Test
  @DisplayName("completeTask inside a rolled-back transaction leaves the task open (shared transaction)")
  public void completeTaskInRolledBackTransactionLeavesTaskOpen() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeAsyncTask(aggregate, taskId);
          throw new RuntimeException("test rollback");
        }));

    // the engine shares the caller's transaction: the rolled-back signal never
    // happened - the execution is still parked at the task
    Thread.sleep(500);
    final var instanceId = instanceIdOf(aggregateId);
    assertNotNull(instanceId, "the process must still be active");
    assertEquals(
        List.of("AP_Task"),
        runtimeService.getActiveActivityIds(instanceId),
        "the task has to stay open after the rollback");

    // the retried completion converges
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeAsyncTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), "AsyncProcess to end after the retried completeTask");

  }

  @Test
  @DisplayName("completeTask of an unknown task raises the guiding TaskNotFoundException")
  public void completeUnknownTaskRaisesGuidingException() {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);

    final var exception = assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeAsyncTask(aggregate, "no-such-task");
        }));
    assertTrue(
        exception.getMessage().contains("no-such-task"),
        "expected the unknown task to be named but got: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("@TaskEvent: CANCELED is delivered when the open task's activity is canceled")
  public void taskEventCanceledDeliveredOnCancellation() throws Exception {

    final var aggregateId = startSecondaryProcess("CancelEventProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run (CREATED event) and commit the task id");
    assertEquals("event-created", repository.findById(aggregateId).orElseThrow().getResults());

    // canceling the whole instance cancels the parked activity - the END listener
    // delivers CANCELED to the subscribing handler within the same transaction
    final var instanceId = instanceIdOf(aggregateId);
    transactionTemplate.executeWithoutResult(status -> runtimeService
        .deleteProcessInstance(instanceId, "story-22 test cancellation"));

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("event-canceled");
        },
        "the CANCELED event to be delivered to the handler");

  }

  @Test
  @DisplayName("Awareness edge cases and gone-task tolerance of phase two")
  public void awarenessAndPhaseTwoEdgeCases() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    @SuppressWarnings("unchecked")
    final var c7ProcessService = (io.vanillabp.camunda7.processservice.Camunda7ProcessService<TaskTestAggregate>) applicationContext
        .getBean("Camunda7_ProcessService_c7");

    // ACTIVE: execution exists and the business key matches
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE,
        c7ProcessService.awarenessOfTask(aggregateId, taskId));
    // UNKNOWN: no such execution
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfTask(aggregateId, "999999999"));
    // UNKNOWN: execution exists but belongs to ANOTHER workflow aggregate
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfTask(-1L, taskId));

    // phase two tolerates a gone task (stale outbox entry - warned no-op)
    transactionTemplate.executeWithoutResult(status -> {
      c7ProcessService.completeTaskPhaseTwo("c7-it", "AsyncProcess", null, aggregateId, "999999999");
      c7ProcessService.cancelTaskPhaseTwo("c7-it", "AsyncProcess", null, aggregateId, "999999999", "ERR");
    });

    // cleanup: complete the still-open task
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeAsyncTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), "AsyncProcess to end");

  }

  @Test
  @DisplayName("Send/business-rule/script/user/receive tasks get their async flags at parse time")
  public void mixedTaskTypesGetAsyncFlags() {

    final var definition = processEngine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey("MixedProcess")
        .tenantIdIn(MODULE_ID)
        .latestVersion()
        .singleResult();
    assertNotNull(definition, "MixedProcess deployed");
    final var parsed = ((org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity) processEngine
        .getRepositoryService()
        .getProcessDefinition(definition.getId()));

    for (final var activityId : List.of("MX_Send", "MX_Rule", "MX_Script")) {
      final var activity = parsed.findActivity(activityId);
      assertTrue(activity.isAsyncBefore(), activityId
          + " asyncBefore");
      assertTrue(activity.isAsyncAfter(), activityId
          + " asyncAfter");
    }
    for (final var activityId : List.of("MX_User", "MX_Receive")) {
      final var activity = parsed.findActivity(activityId);
      assertTrue(activity.isAsyncAfter(), activityId
          + " asyncAfter");
    }

  }

  @Test
  @DisplayName("Multi-instance: collection from an aggregate attribute, index/total/element bound")
  public void multiInstanceBindings() throws Exception {

    final var aggregateId = startSecondaryProcess(
        "MultiInstanceProcess", true, List.of("a", "b", "c"));
    awaitUntil(() -> processEnded(aggregateId), "MultiInstanceProcess to end");

    assertEquals(
        "a0/3|b1/3|c2/3",
        repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("The parse listener forces async-before/after onto service tasks")
  public void asyncBeforeAndAfterForcedOntoServiceTasks() {

    final var configuration = (ProcessEngineConfigurationImpl) processEngine
        .getProcessEngineConfiguration();
    // deployment-cache access needs an engine command context
    final var processDefinition = configuration
        .getCommandExecutorTxRequired()
        .execute(commandContext -> configuration
            .getDeploymentCache()
            .findDeployedLatestProcessDefinitionByKeyAndTenantId("TaskProcess", MODULE_ID));

    final var happyTask = processDefinition.findActivity("TP_Happy");
    assertTrue(happyTask.isAsyncBefore(), "asyncBefore forced onto the service task");
    assertTrue(happyTask.isAsyncAfter(), "asyncAfter forced onto the service task");

  }

}
