package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow started past VanillaBP, against a real embedded Camunda 7 engine. Anybody
 * with access to the engine can start a process which VanillaBP knows, and give it a
 * business key of their own choosing - a timer start event does not stop the engine's own
 * start API, and a conditional start can even be evaluated with a business key.
 * <p>
 * Such a workflow used to get nothing: the adapter read the key as a sign that the
 * application had started the workflow and returned. The first task then failed with "no
 * workflow aggregate having the id ... was found", which blames a deletion for a workflow
 * that never had an aggregate. These tests hold the rule which replaced that: a start is
 * the application's own one where the id already has an aggregate, and nothing else says
 * so.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-foreign-start-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, and an engine outliving its test keeps its job executor running against a database
// the next classes work on
@DirtiesContext
public class Camunda7ForeignStartIT {

  /**
   * The Camunda tenant the workflow module is deployed to under
   * {@code name-clash-avoidance: by-adapter}, which every start from outside has to name.
   */
  private static final String TENANT = "c7-it";

  @Autowired
  private ForeignTimerRepository timerRepository;

  @Autowired
  private ForeignSignalRepository signalRepository;

  @Autowired
  private ForeignConditionRepository conditionRepository;

  @Autowired
  private ForeignNumericIdRepository numericIdRepository;

  @Autowired
  private ForeignTimerWorkflowService timerWorkflowService;

  @Autowired
  private RuntimeService runtimeService;

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
  @DisplayName("A timer-started process somebody else starts gets an aggregate under its business key")
  public void aTimerProcessStartedFromOutside() throws Exception {

    runtimeService
        .createProcessInstanceByKey("ForeignTimerProcess")
        .processDefinitionTenantId(TENANT)
        .businessKey("foreign-timer-key")
        .execute();

    awaitUntil(
        () -> timerRepository.findById("foreign-timer-key").isPresent(),
        "the workflow aggregate of the foreign start to be created");

    // the key somebody else chose IS the id of the aggregate, so the task following the
    // start event finds it and the workflow runs on
    awaitUntil(
        () -> "recordForeignTimerStart".equals(
            timerRepository
                .findById("foreign-timer-key")
                .map(ForeignTimerAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the foreign start to be processed");

  }

  @Test
  @DisplayName("A signal-started process somebody else starts gets an aggregate under its business key")
  public void aSignalProcessStartedFromOutside() throws Exception {

    runtimeService
        .createProcessInstanceByKey("ForeignSignalProcess")
        .processDefinitionTenantId(TENANT)
        .businessKey("foreign-signal-key")
        .execute();

    awaitUntil(
        () -> "recordForeignSignalStart".equals(
            signalRepository
                .findById("foreign-signal-key")
                .map(ForeignSignalAggregate::getProcessedBy)
                .orElse(null)),
        "the workflow aggregate of the foreign signal start to be created and processed");

  }

  @Test
  @DisplayName("A condition the engine evaluates with a business key gets an aggregate under that key")
  public void aConditionEvaluatedWithABusinessKey() throws Exception {

    // the engine starts this one itself, and the caller of the evaluation names the
    // business key - a BPMS-initiated start carrying a key nobody derived from an
    // aggregate
    runtimeService
        .createConditionEvaluation()
        .setVariable("foreignStartReady", Boolean.TRUE)
        .processInstanceBusinessKey("foreign-condition-key")
        .tenantId(TENANT)
        .evaluateStartConditions();

    awaitUntil(
        () -> "recordForeignConditionStart".equals(
            conditionRepository
                .findById("foreign-condition-key")
                .map(ForeignConditionAggregate::getProcessedBy)
                .orElse(null)),
        "the workflow aggregate of the evaluated condition to be created and processed");

  }

  @Test
  @DisplayName("A signal the engine broadcasts without a business key still builds an aggregate of its own")
  public void aBroadcastWithoutABusinessKey() throws Exception {

    final var before = signalRepository.count();

    runtimeService
        .createSignalEvent("ForeignStartSignal")
        .tenantId(TENANT)
        .send();

    awaitUntil(
        () -> signalRepository.count() > before,
        "the workflow aggregate of the broadcast to be created");

    final var started = signalRepository
        .findAll()
        .stream()
        .filter(aggregate -> !"foreign-signal-key".equals(aggregate.getId()))
        .findFirst()
        .orElseThrow();
    // nobody named this workflow, so VanillaBP did
    assertFalse(started.getId().isBlank(), "the aggregate of a broadcast start has an id");
    awaitUntil(
        () -> "recordForeignSignalStart".equals(
            signalRepository
                .findById(started.getId())
                .map(ForeignSignalAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the broadcast start to be processed");

  }

  @Test
  @DisplayName("The application's own start of such a process is not mistaken for a foreign one")
  public void theApplicationsOwnStart() throws Exception {

    // the aggregate is written and the instance started in ONE transaction here, so this
    // is also the proof that the aggregate is readable while that transaction runs
    timerWorkflowService.startTheWorkflow("started-by-the-application");

    awaitUntil(
        () -> "recordForeignTimerStart".equals(
            timerRepository
                .findById("started-by-the-application")
                .map(ForeignTimerAggregate::getProcessedBy)
                .orElse(null)),
        "the task of the workflow the application started to be processed");

    // the value the application wrote before starting is still there. A start VanillaBP
    // had taken for a foreign one would have built a second aggregate over this one, so
    // this is the proof that the aggregate is readable in the transaction writing it
    assertEquals(
        "the application",
        timerRepository.findById("started-by-the-application").orElseThrow().getStartedBy(),
        "what the application wrote into the aggregate survived its own start");

  }

  @Test
  @DisplayName("A business key which cannot be an id is refused, and the message says what to do")
  public void aBusinessKeyWhichCannotBeAnId() {

    final var before = numericIdRepository.count();

    // the id of this aggregate is a number the persistence layer assigns, so a text key
    // can never be one. Naming the workflow something else would overwrite the key its
    // starter chose
    final var refused = assertThrows(
        RuntimeException.class,
        () -> runtimeService
            .createProcessInstanceByKey("ForeignNumericIdProcess")
            .processDefinitionTenantId(TENANT)
            .businessKey("ORDER-4711")
            .execute());

    final var message = String.valueOf(refused.getMessage()) + String
        .valueOf(refused.getCause() == null ? "" : refused.getCause().getMessage());
    assertTrue(message.contains("ORDER-4711"), "the key is named: "
        + message);
    assertTrue(
        message.contains("ProcessService"),
        "the way out is named: "
            + message);
    assertTrue(
        message.contains("ForeignNumericIdProcess"),
        "the BPMN process is named: "
            + message);

    assertEquals(before, numericIdRepository.count(), "a refused start leaves no aggregate behind");
    assertEquals(
        0,
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey("ORDER-4711")
            .count(),
        "a refused start leaves no process instance behind");

  }

}
