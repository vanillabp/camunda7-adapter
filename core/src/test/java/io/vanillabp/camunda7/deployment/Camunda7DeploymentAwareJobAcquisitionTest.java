package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.camunda.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two nodes of one application on one database, as during a rolling update. Each node is an
 * engine of its own, deployment-aware as the platform modules build it, and deploys its
 * workflow module through the adapter.
 * <p>
 * What a node fetches is asked with the engine's own acquisition command, the one its job
 * executor runs. So the answer does not depend on which node's polling happens to come
 * first.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DeploymentAwareJobAcquisitionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "LoanApproval";

  private static final String ADAPTER_ID = "camunda7";

  private String jdbcUrl;

  private final List<ProcessEngine> engines = new ArrayList<>();

  @BeforeEach
  public void oneDatabase() {

    jdbcUrl = "jdbc:h2:mem:deployment-aware-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime());

  }

  @AfterEach
  public void stopEveryNode() {

    engines.forEach(ProcessEngine::close);

  }

  /**
   * Boots one node on the shared database. Its job executor stays off: the test runs the
   * acquisition itself.
   */
  private ProcessEngine node(
      final String name) {

    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setJdbcUrl(jdbcUrl);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setProcessEngineName("%s-%s".formatted(name, System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setJobExecutorDeploymentAware(true);
    configuration.setHistoryTimeToLive("P30D");
    final var engine = configuration.buildProcessEngine();
    engines.add(engine);
    return engine;

  }

  /** A restart: the node forgets what it registered, the database stays. */
  private ProcessEngine restart(
      final ProcessEngine node,
      final String name) {

    node.close();
    engines.remove(node);
    return node(name);

  }

  /**
   * A model whose first step waits for a timer. The timer is the job the nodes compete for.
   *
   * @param bpmnProcessId The id of the process
   * @param taskDefinition What the service task behind the timer is wired to, so two versions
   *          differ
   */
  private static String bpmnOf(
      final String bpmnProcessId,
      final String taskDefinition) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%1$s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>toWait</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="toWait" sourceRef="start" targetRef="wait" />
            <bpmn:intermediateCatchEvent id="wait">
              <bpmn:incoming>toWait</bpmn:incoming><bpmn:outgoing>toTask</bpmn:outgoing>
              <bpmn:timerEventDefinition id="timer"><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
            </bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="toTask" sourceRef="wait" targetRef="task" />
            <bpmn:serviceTask id="task" camunda:expression="${%2$s}"><bpmn:incoming>toTask</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
            <bpmn:sequenceFlow id="toEnd" sourceRef="task" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(bpmnProcessId, taskDefinition);

  }

  /**
   * The deployment pipeline of one workflow module on one node, as the core runs it at boot,
   * with the management service handed over as both platform modules do.
   */
  private static void deployThroughTheAdapter(
      final ProcessEngine node,
      final NameClashAvoidance mode,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, node.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .scoping(new RecordingScoping(mode))
            .build(), new Camunda7TaskRegistry());
    service.setRuntimeService(node.getRuntimeService());
    service.setManagementService(node.getManagementService());
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(bpmnOf(bpmnProcessId, taskDefinition)
            .getBytes(StandardCharsets.UTF_8)));
    final var context = service
        .prepareBpmn(workflowModuleId, null, "%s.bpmn".formatted(bpmnProcessId), bpmnProcessId, model);
    service.deployResources(workflowModuleId, context);

  }

  private static void deployThroughTheAdapter(
      final ProcessEngine node,
      final String taskDefinition) {

    deployThroughTheAdapter(node, NameClashAvoidance.NONE, MODULE, PROCESS_ID, taskDefinition);

  }

  /**
   * Starts a workflow on the latest version the database holds and makes its timer due.
   *
   * @return The id of the timer job
   */
  private static String aDueTimerOn(
      final ProcessEngine node,
      final String bpmnProcessId,
      final String tenantId) {

    var start = node
        .getRuntimeService()
        .createProcessInstanceByKey(bpmnProcessId);
    start = (tenantId == null)
        ? start.processDefinitionWithoutTenantId()
        : start.processDefinitionTenantId(tenantId);
    final var instance = start.execute();
    final var job = node
        .getManagementService()
        .createJobQuery()
        .processInstanceId(instance.getId())
        .singleResult();
    node
        .getManagementService()
        .setJobDuedate(job.getId(), new Date(System.currentTimeMillis() - 1000));
    return job.getId();

  }

  private static String aDueTimerOn(
      final ProcessEngine node) {

    return aDueTimerOn(node, PROCESS_ID, null);

  }

  /** What the node's job executor would fetch now. The fetched jobs are locked by it. */
  private static AcquiredJobs acquireOn(
      final ProcessEngine node) {

    final var configuration = (ProcessEngineConfigurationImpl) node.getProcessEngineConfiguration();
    return configuration
        .getCommandExecutorTxRequired()
        .execute(new AcquireJobsCmd(configuration.getJobExecutor()));

  }

  @Test
  @DisplayName("During a rolling update the old node leaves the job of the new version alone, and the new node fetches it")
  public void theOldNodeLeavesTheNewVersionAlone() {

    final var oldNode = node("old");
    deployThroughTheAdapter(oldNode, "checkCredit");
    final var newNode = node("new");
    deployThroughTheAdapter(newNode, "checkCreditAndFraud");

    final var jobOfTheNewVersion = aDueTimerOn(newNode);

    assertFalse(
        acquireOn(oldNode).contains(jobOfTheNewVersion),
        "the old node does not know the deployment a newer node made after it started, so it must not "
            + "fetch a job whose task it has no method for");
    assertTrue(
        acquireOn(newNode).contains(jobOfTheNewVersion),
        "the node which deployed the new version serves its jobs");

  }

  @Test
  @DisplayName("The old node keeps serving the version it deployed itself")
  public void theOldNodeKeepsServingItsOwnVersion() {

    final var oldNode = node("old");
    deployThroughTheAdapter(oldNode, "checkCredit");
    final var jobOfTheOldVersion = aDueTimerOn(oldNode);
    final var newNode = node("new");
    deployThroughTheAdapter(newNode, "checkCreditAndFraud");

    assertTrue(
        acquireOn(oldNode).contains(jobOfTheOldVersion),
        "the deployment the old node made at its own boot stays registered with it");

  }

  @Test
  @DisplayName("After a restart the new node fetches the timer of a workflow started on version 1")
  public void aTimerOnVersionOneIsFetchedAfterTheRestart() {

    final var oldNode = node("old");
    deployThroughTheAdapter(oldNode, "checkCredit");
    final var timerOnVersionOne = aDueTimerOn(oldNode);
    oldNode.close();
    engines.remove(oldNode);

    var newNode = node("new");
    deployThroughTheAdapter(newNode, "checkCreditAndFraud");
    // the restart deploys nothing new. The deploy command registers only the duplicate it
    // finds, which is version 2
    newNode = restart(newNode, "new");
    deployThroughTheAdapter(newNode, "checkCreditAndFraud");

    assertTrue(
        acquireOn(newNode).contains(timerOnVersionOne),
        "a workflow on an older version must keep running, or it stops without an error and without "
            + "an incident");

  }

  @Test
  @DisplayName("Without the adapter's registration a deployment-aware node never fetches the timer of version 1")
  public void withoutTheRegistrationTheTimerOnVersionOneStays() {

    final var oldNode = node("old");
    deployThroughTheAdapter(oldNode, "checkCredit");
    final var timerOnVersionOne = aDueTimerOn(oldNode);

    // the flag alone: a node which deploys version 2 with the engine's own deploy command,
    // which registers only the deployment it makes
    final var newNode = node("new");
    newNode
        .getRepositoryService()
        .createDeployment()
        .name(MODULE)
        .enableDuplicateFiltering(true)
        .addString("%s.bpmn".formatted(PROCESS_ID), bpmnOf(PROCESS_ID, "checkCreditAndFraud"))
        .deploy();

    assertFalse(
        acquireOn(newNode).contains(timerOnVersionOne),
        "this is what the registration of the older versions is for: the flag alone leaves the timer of "
            + "version 1 to nobody");

  }

  @Test
  @DisplayName("A deployment of another workflow module or another application is not fetched")
  public void deploymentsOfOthersAreNotFetched() {

    final var otherApplication = node("other-application");
    // a second workflow module of another application, deployed under its own name
    deployThroughTheAdapter(otherApplication, NameClashAvoidance.NONE, "fraud-detection", "FraudCheck", "checkFraud");
    final var jobOfTheOtherModule = aDueTimerOn(otherApplication, "FraudCheck", null);
    // somebody who deploys the same process id under a name of their own, like a modelling tool
    otherApplication
        .getRepositoryService()
        .createDeployment()
        .name("some-modelling-tool")
        .addString("%s.bpmn".formatted(PROCESS_ID), bpmnOf(PROCESS_ID, "checkSomethingElse"))
        .deploy();
    final var jobOfTheForeignDeployment = aDueTimerOn(otherApplication);

    final var ourNode = node("ours");
    deployThroughTheAdapter(ourNode, "checkCredit");
    final var ourJob = aDueTimerOn(ourNode);

    final var fetched = acquireOn(ourNode);
    assertFalse(fetched.contains(jobOfTheOtherModule), "another workflow module's deployment is not ours");
    assertFalse(
        fetched.contains(jobOfTheForeignDeployment),
        "a deployment of the same process id under another name is not ours");
    assertTrue(fetched.contains(ourJob), "our own deployment is fetched");

  }

  @Test
  @DisplayName("After the upgrade from version 1 the timer of a workflow version 1 started is fetched")
  public void aTimerOfAVersionOneDeploymentIsFetchedAfterTheUpgrade() {

    // what VanillaBP 1 deployed: the APPLICATION name as source, the workflow module id as
    // name, and the module id as tenant
    final var versionOneNode = node("version-1");
    versionOneNode
        .getRepositoryService()
        .createDeployment()
        .name(MODULE)
        .source("my-loan-application")
        .tenantId(MODULE)
        .enableDuplicateFiltering(true)
        .addString("%s.bpmn".formatted(PROCESS_ID), bpmnOf(PROCESS_ID, "checkCredit"))
        .deploy();
    final var timerOfVersionOne = aDueTimerOn(versionOneNode, PROCESS_ID, MODULE);
    // the same name in another tenant belongs to another scope and stays unregistered
    versionOneNode
        .getRepositoryService()
        .createDeployment()
        .name(MODULE)
        .source("my-loan-application")
        .tenantId("another-tenant")
        .addString("%s.bpmn".formatted(PROCESS_ID), bpmnOf(PROCESS_ID, "checkCredit"))
        .deploy();
    final var timerInAnotherTenant = aDueTimerOn(versionOneNode, PROCESS_ID, "another-tenant");
    versionOneNode.close();
    engines.remove(versionOneNode);

    // version 2 keeps the tenant per workflow module, which is its default
    final var upgradedNode = node("version-2");
    deployThroughTheAdapter(upgradedNode, NameClashAvoidance.BY_ADAPTER, MODULE, PROCESS_ID, "checkCreditAndFraud");
    assertEquals(
        2,
        upgradedNode
            .getRepositoryService()
            .createDeploymentQuery()
            .deploymentName(MODULE)
            .tenantIdIn(MODULE)
            .count(),
        "version 2 deployed a model of its own next to the one version 1 left behind");

    final var fetched = acquireOn(upgradedNode);
    assertTrue(
        fetched.contains(timerOfVersionOne),
        "the deployment version 1 made carries another source, but the module's name and tenant, so its "
            + "workflows keep running after the upgrade");
    assertFalse(fetched.contains(timerInAnotherTenant), "a deployment in another tenant is not this module's");

  }

}
