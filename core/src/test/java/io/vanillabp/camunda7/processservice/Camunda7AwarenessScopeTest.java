package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 104: the awareness probes of this adapter answer for ITS OWN scope, which is
 * what the election contract of {@code MigratableProcessService} demands (story 105).
 * <p>
 * A Camunda 7 business key is the workflow-aggregate id, and an engine may hold
 * processes this adapter never wired: models of another application sharing the
 * database, or definitions left by a version of this application which knew other
 * workflow modules. Answering {@code ACTIVE} for one of those claims a workflow this
 * adapter cannot serve, and in a migration it wins the election against the BPMS which
 * really holds it.
 * <p>
 * What the adapter compares is what the wiring registered: the process definition keys
 * of its workflow modules, and the tenant each module runs in. The engine here is a real
 * one, in memory, because the queries doing the comparison are the subject.
 * <p>
 * <b>What this cannot fix</b>, and story 104 says so in its result: two workflow modules
 * of the SAME adapter are both its own scope, so a foreign-module instance carrying the
 * same aggregate id is still answered {@code ACTIVE}. The probe is not told which module
 * is being asked about, which only the deferred SPI change of story 105 would supply.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AwarenessScopeTest {

  private static final String OWN_PROCESS = "OwnProcess";

  private static final String FOREIGN_PROCESS = "ForeignProcess";

  private static final String MODULE = "own-module";

  /**
   * Without a name-clash-avoidance support the adapter behaves like {@code by-adapter},
   * its version-1 mode: the workflow module id IS the Camunda tenant. That is what makes
   * the tenant half of the scope observable here without stubbing the core's scoping.
   */
  private static final String OWN_TENANT = MODULE;

  /**
   * Another application on the same engine, with a process definition of the same key.
   */
  private static final String FOREIGN_TENANT = "someone-else";

  private static final String AGGREGATE_ID = "1";

  private ProcessEngine engine;

  private Camunda7ProcessService<Object> processService;

  private Camunda7TaskRegistry registry;

  /**
   * A process with a user task, so an instance stays running and offers both a task and
   * an execution to probe.
   */
  private static String bpmn(
      final String processId) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            id="defs_%s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
            <bpmn:userTask id="wait"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:userTask>
            <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(processId, processId);

  }

  @BeforeEach
  public void bootTheEngine() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:awareness-scope-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistory("full");
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();
    deploy(OWN_TENANT, OWN_PROCESS, FOREIGN_PROCESS);
    // the same process key in the tenant of somebody else - what the scope has to tell
    // apart although the definition key is identical
    deploy(FOREIGN_TENANT, OWN_PROCESS);
    registry = new Camunda7TaskRegistry();
    registry.setAdapterId("c7");
    // what the wiring of THIS adapter registered: one workflow module, one process.
    // The foreign process exists in the engine and was never wired here.
    registry.registerProcess(MODULE, OWN_PROCESS, OWN_PROCESS);
    processService = new Camunda7ProcessService<>(
        "c7", engine.getRuntimeService(), engine.getTaskService(), engine.getRepositoryService(), engine
            .getHistoryService());
    processService.setTaskRegistry(registry);

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private void deploy(
      final String tenantId,
      final String... processIds) {

    var deployment = engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(tenantId);
    for (final var processId : processIds) {
      deployment = deployment
          .addString(
              processId
                  + ".bpmn",
              bpmn(processId));
    }
    deployment.deploy();

  }

  private String start(
      final String processId) {

    return start(processId, OWN_TENANT);

  }

  private String start(
      final String processId,
      final String tenantId) {

    return engine
        .getRuntimeService()
        .createProcessInstanceByKey(processId)
        .processDefinitionTenantId(tenantId)
        .businessKey(AGGREGATE_ID)
        .execute()
        .getId();

  }

  private void finish(
      final String processInstanceId) {

    final var task = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(processInstanceId)
        .singleResult();
    engine.getTaskService().complete(task.getId());

  }

  @Test
  @DisplayName("A workflow of this adapter's own scope is claimed")
  public void ownWorkflowIsClaimed() {

    start(OWN_PROCESS);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService.awarenessOfWorkflow(null, AGGREGATE_ID),
        "the adapter holds this workflow");

  }

  @Test
  @DisplayName("A workflow of a process this adapter never wired is not claimed, however the business key reads")
  public void aForeignWorkflowIsNotClaimed() {

    start(FOREIGN_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(null, AGGREGATE_ID),
        "the business key matches and the process is not this adapter's - the election has to continue");

  }

  @Test
  @DisplayName("An ended workflow of the own scope is COMPLETED, an ended foreign one stays unknown")
  public void theHistoryIsScopedAsWell() {

    final var own = start(OWN_PROCESS);
    finish(own);

    assertEquals(
        WorkflowAwareness.COMPLETED,
        processService.awarenessOfWorkflow(null, AGGREGATE_ID),
        "an ended workflow of this adapter is reported as ended, not as unknown");

    final var foreign = start(FOREIGN_PROCESS);
    finish(foreign);
    engine
        .getHistoryService()
        .deleteHistoricProcessInstance(own);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(null, AGGREGATE_ID),
        "the history of a foreign process says nothing about this adapter");

  }

  @Test
  @DisplayName("A user task of a foreign process is not claimed, one of the own scope is")
  public void userTasksAreScoped() {

    final var foreign = start(FOREIGN_PROCESS);
    final var foreignTask = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(foreign)
        .singleResult();

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfUserTask(AGGREGATE_ID, foreignTask.getId()),
        "a task key of this engine is addressable and still not this adapter's business");

    final var own = start(OWN_PROCESS);
    final var ownTask = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(own)
        .singleResult();

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService.awarenessOfUserTask(AGGREGATE_ID, ownTask.getId()));

  }

  @Test
  @DisplayName("A task of a foreign process is not claimed, one of the own scope is")
  public void serviceTasksAreScoped() {

    final var foreign = start(FOREIGN_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfTask(AGGREGATE_ID, foreign),
        "the execution of a foreign workflow is not this adapter's task");

    final var own = start(OWN_PROCESS);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService.awarenessOfTask(AGGREGATE_ID, own));

  }

  @Test
  @DisplayName("The wrong business key stays unknown, scope or not")
  public void theBusinessKeyStillCounts() {

    start(OWN_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(null, "another-aggregate"));

  }

  @Test
  @DisplayName("The same process key in another tenant is not this adapter's scope")
  public void theTenantIsPartOfTheScope() {

    start(OWN_PROCESS, FOREIGN_TENANT);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(null, AGGREGATE_ID),
        "the definition key is the adapter's own and the tenant is somebody else's");

  }

  @Test
  @DisplayName("Without a wiring registry the adapter answers as it did before story 104")
  public void withoutARegistryNothingChanges() {

    final var unwired = new Camunda7ProcessService<>(
        "c7", engine.getRuntimeService(), engine.getTaskService(), engine.getRepositoryService(), engine
            .getHistoryService());
    start(FOREIGN_PROCESS);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        unwired.awarenessOfWorkflow(null, AGGREGATE_ID),
        "an adapter which wired nothing cannot tell scopes apart and says what it always said");

  }

}
