package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which ends this engine reports as a canceled workflow, measured against a real embedded
 * engine. A workflow is canceled when the instance is gone without having reached an
 * element of its own. The engine's history calls such an instance terminated, and each test
 * reads that state next to the kind the application hears.
 * <p>
 * A modeller who looks at the model would call the terminate end event and the interrupting
 * event subprocess a cancelation, but neither of them is one. Both end the instance at an
 * element of the model, so the application hears a completion. Camunda 8 answers the same.
 * That is why the SPI promises no mapping of modelled paths any more, and every adapter
 * measures its own engine.
 * <p>
 * An instance which somebody removes from outside is canceled, with a reason or without
 * one. Without a reason is what the REST API and the web app do.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database. Contexts are cached and live in parallel. A foreign job executor on the
    // same H2 database would compete for the timers which this test waits for.
    "spring.datasource.url=jdbc:h2:mem:c7-end-kind-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7WorkflowEndKindIT {

  @Autowired
  private EndKindTerminateWorkflowService terminateWorkflowService;

  @Autowired
  private EndKindTerminateRepository terminateRepository;

  @Autowired
  private EndKindEventSubprocessWorkflowService eventSubprocessWorkflowService;

  @Autowired
  private EndKindEventSubprocessRepository eventSubprocessRepository;

  @Autowired
  private EndKindDeleteWorkflowService deleteWorkflowService;

  @Autowired
  private EndKindDeleteRepository deleteRepository;

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private ProcessEngine processEngine;

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
  @DisplayName("A terminate end event completes the workflow, it does not cancel it")
  public void aTerminateEndEventCompletesTheWorkflow() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> terminateWorkflowService.startWorkflow().getId());

    awaitUntil(
        () -> endedAs(() -> terminateRepository.findById(aggregateId).orElseThrow().getEndedAs()) != null,
        "the workflow to run into its terminate end event");

    // measured twice on 2026-09-20 against the embedded engine of this build. The execution
    // carries NO delete reason here. So the application hears a completion, and the
    // terminate end event is named as the end event reached.
    assertEquals(
        "COMPLETED/Event_Terminate",
        endedAs(() -> terminateRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "the engine ends a terminated instance without a delete reason");
    assertEquals(
        HistoricProcessInstance.STATE_COMPLETED,
        historicStateOf(aggregateId),
        "the history calls the instance completed as well");

  }

  @Test
  @DisplayName("An interrupting event subprocess completes the workflow, it does not cancel it")
  public void anInterruptingEventSubprocessCompletesTheWorkflow() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> eventSubprocessWorkflowService.startWorkflow().getId());

    awaitUntil(
        () -> endedAs(
            () -> eventSubprocessRepository.findById(aggregateId).orElseThrow().getEndedAs()) != null,
        "the event subprocess to interrupt the waiting workflow");

    // measured twice on 2026-09-20, same build and same engine. The waiting token is taken
    // away, and the instance still ends without a delete reason. The element named is the
    // one the instance ended at. That is the event subprocess and not an end event, so a
    // reader of that id may not assume it is one.
    assertEquals(
        "COMPLETED/Activity_TakeOver",
        endedAs(() -> eventSubprocessRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "an interrupted instance ends without a delete reason too");
    assertEquals(
        HistoricProcessInstance.STATE_COMPLETED,
        historicStateOf(aggregateId),
        "the history calls the instance completed as well");

  }

  @Test
  @DisplayName("A workflow deleted with a reason is canceled")
  public void aWorkflowDeletedWithAReasonIsCanceled() throws Exception {

    final var aggregateId = startWaitingWorkflow();

    transactionTemplate
        .executeWithoutResult(status -> runtimeService
            .deleteProcessInstance(instanceIdOf(aggregateId), "removed by the end kind test"));

    assertEquals(
        "CANCELED/null",
        endedAs(() -> deleteRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "a delete reason has always meant a cancelation");
    // the Java API tells the engine 'not externally terminated', although a person did it.
    // So the internal state has to count as a cancelation, too
    assertEquals(
        HistoricProcessInstance.STATE_INTERNALLY_TERMINATED,
        historicStateOf(aggregateId),
        "the history calls the deleted instance terminated");

  }

  @Test
  @DisplayName("A workflow deleted without a reason is canceled")
  public void aWorkflowDeletedWithoutAReasonIsCanceled() throws Exception {

    final var aggregateId = startWaitingWorkflow();

    transactionTemplate
        .executeWithoutResult(status -> runtimeService.deleteProcessInstance(instanceIdOf(aggregateId), null));

    // measured on 2026-11-14 against the embedded engine 7.24.0 of this build. The execution
    // carries no delete reason here, but the instance ends without standing at an element
    assertEquals(
        "CANCELED/null",
        endedAs(() -> deleteRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "an instance removed from outside is canceled, also without a reason");
    assertEquals(
        HistoricProcessInstance.STATE_INTERNALLY_TERMINATED,
        historicStateOf(aggregateId),
        "the history calls the deleted instance terminated");

  }

  @Test
  @DisplayName("A workflow deleted the way the REST API and the web app delete it is canceled")
  public void aWorkflowDeletedThroughTheRestApiIsCanceled() throws Exception {

    final var aggregateId = startWaitingWorkflow();

    // the call of the REST API's 'DELETE /process-instance/{id}', which the web app uses as
    // well: no reason, and 'externally terminated'. The REST API has no parameter for a reason
    transactionTemplate
        .executeWithoutResult(status -> runtimeService
            .deleteProcessInstance(instanceIdOf(aggregateId), null, false, true, false, false));

    assertEquals(
        "CANCELED/null",
        endedAs(() -> deleteRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "an instance an operator removes in the web app is canceled");
    assertEquals(
        HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED,
        historicStateOf(aggregateId),
        "the history calls the deleted instance terminated, from outside this time");

  }

  @Test
  @DisplayName("A modification which cancels the last activity without a reason cancels the workflow")
  public void aModificationWithoutAReasonCancelsTheWorkflow() throws Exception {

    final var aggregateId = startWaitingWorkflow();

    transactionTemplate
        .executeWithoutResult(status -> runtimeService
            .createProcessInstanceModification(instanceIdOf(aggregateId))
            .cancelAllForActivity("Event_Wait")
            .execute());

    assertEquals(
        "CANCELED/null",
        endedAs(() -> deleteRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "an instance whose last token an operator takes away is canceled");
    assertEquals(
        HistoricProcessInstance.STATE_INTERNALLY_TERMINATED,
        historicStateOf(aggregateId),
        "the history calls the modified instance terminated");

  }

  private Long startWaitingWorkflow() throws InterruptedException {

    final var aggregateId = transactionTemplate.execute(status -> deleteWorkflowService.startWorkflow().getId());
    // the engine starts the instance after the commit of the caller's transaction
    awaitUntil(() -> findInstanceIdOf(aggregateId) != null, "the workflow to wait at its timer");
    return aggregateId;

  }

  private String instanceIdOf(
      final Long aggregateId) {

    return findInstanceIdOf(aggregateId);

  }

  private String findInstanceIdOf(
      final Long aggregateId) {

    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    return instance == null
        ? null
        : instance.getId();

  }

  private String historicStateOf(
      final Long aggregateId) {

    return processEngine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult()
        .getState();

  }

  private String endedAs(
      final Supplier<String> read) {

    return transactionTemplate.execute(status -> read.get());

  }

}
