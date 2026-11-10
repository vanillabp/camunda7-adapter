package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The first start of version 2 on an engine which version 1 used. Version 1 deployed the
 * workflow module and started workflows. Version 2 deploys the same file again and has to
 * serve those workflows to their end, on the version they were started on.
 * <p>
 * The first step writes what version 1 left in the database, without version 1 itself: a
 * plain engine deploys the file as version 1 did. The deployment is named after the workflow
 * module, its source is the application's name, its tenant is the workflow module, and it
 * holds the original bytes of the file. Then two workflows are started. Both wait at a user
 * task. Version 1 would have told the application about that task already, so nothing is
 * missing there.
 * <p>
 * The second step boots version 2 on the same in-memory database.
 * <code>DB_CLOSE_DELAY=-1</code> keeps it alive in between.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda7UpgradeFromVersionOneIT {

  private static final String JDBC_URL = "jdbc:h2:mem:c7-upgrade-from-v1;DB_CLOSE_DELAY=-1";

  private static final String WORKFLOW_MODULE = "c7-it";

  private static final String FILE = "c7-it/upgrade-from-v1/upgrade-from-v1.bpmn";

  /** The workflow which goes on after its user task is completed. */
  private static final String COMPLETED = "upgrade-from-v1-completed";

  /** The workflow whose user task is canceled by a message. */
  private static final String CANCELED = "upgrade-from-v1-canceled";

  @Test
  @Order(1)
  @DisplayName("Version 1 deployed the model and started two workflows, which wait at a user task")
  public void versionOneLeftTwoWorkflows() throws Exception {

    final var configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
        .createStandaloneProcessEngineConfiguration()
        .setProcessEngineName("vanillabp-1-application")
        .setJdbcUrl(JDBC_URL)
        .setJdbcDriver("org.h2.Driver")
        .setJdbcUsername("sa")
        .setJdbcPassword("")
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
        .setJobExecutorActivate(false);
    // the engine refuses a model without a history time to live
    configuration.setHistoryTimeToLive("P1D");
    final var engine = configuration.buildProcessEngine();
    try (var file = getClass().getClassLoader().getResourceAsStream(FILE)) {
      // what the deployment of version 1 wrote: the original bytes, the module as name and
      // tenant, and the application's name as source
      engine
          .getRepositoryService()
          .createDeployment()
          .name(WORKFLOW_MODULE)
          .source("a-version-1-application")
          .tenantId(WORKFLOW_MODULE)
          .enableDuplicateFiltering(true)
          .addInputStream("upgrade-from-v1.bpmn", file)
          .deploy();
      for (final var businessKey : new String[]{
          COMPLETED, CANCELED
      }) {
        engine
            .getRuntimeService()
            .createProcessInstanceByKey("UpgradeFromV1Process")
            .processDefinitionTenantId(WORKFLOW_MODULE)
            .businessKey(businessKey)
            .execute();
      }
      assertEquals(2, engine.getTaskService().createTaskQuery().taskDefinitionKey("UFV_Review").count());
    } finally {
      engine.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("Version 2 deploys the unchanged file as a new version, and the workflows stay on version 1")
  public void theFirstStartDeploysANewVersion() throws Exception {

    final var application = boot();
    try {
      final var repository = application.getBean(RepositoryService.class);
      final var versions = repository
          .createProcessDefinitionQuery()
          .processDefinitionKey("UpgradeFromV1Process")
          .tenantIdIn(WORKFLOW_MODULE)
          .list()
          .stream()
          .sorted(Comparator.comparingInt(ProcessDefinition::getVersion))
          .toList();
      assertEquals(2, versions.size(), "version 2 deploys the same file as a version of its own");

      final var ofVersionOne = repository.createDeploymentQuery().deploymentId(versions.get(0).getDeploymentId())
          .singleResult();
      final var ofVersionTwo = repository.createDeploymentQuery().deploymentId(versions.get(1).getDeploymentId())
          .singleResult();
      assertEquals(WORKFLOW_MODULE, ofVersionTwo.getName(), "the name stays the workflow module");
      assertEquals(ofVersionOne.getTenantId(), ofVersionTwo.getTenantId(), "and so does the tenant");
      assertEquals("c7:"
          + WORKFLOW_MODULE, ofVersionTwo.getSource(), "the source names the adapter id and the module");
      assertNotEquals(
          new String(repository.getResourceAsStream(ofVersionOne.getId(), "upgrade-from-v1.bpmn").readAllBytes()),
          new String(repository.getResourceAsStream(ofVersionTwo.getId(), "upgrade-from-v1.bpmn").readAllBytes()),
          "version 2 deploys the model it prepared, not the bytes of the file");

      final var runtime = application.getBean(RuntimeService.class);
      assertEquals(
          2,
          runtime.createProcessInstanceQuery().processDefinitionId(versions.get(0).getId()).count(),
          "the workflows of version 1 stay on the version they were started on");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(3)
  @DisplayName("A workflow version 1 started runs to its end under version 2, through the called process")
  public void aWorkflowOfVersionOneRunsToItsEnd() throws Exception {

    final var application = boot();
    try {
      final var transaction = application.getBean(TransactionTemplate.class);
      final var repository = application.getBean(UpgradeFromV1Repository.class);
      transaction.executeWithoutResult(status -> {
        repository.save(aggregate(COMPLETED));
        repository.save(aggregate(CANCELED));
      });

      final var taskId = reviewOf(application, COMPLETED);
      transaction
          .executeWithoutResult(status -> application
              .getBean(UpgradeFromV1WorkflowService.class)
              .completeReview(COMPLETED, taskId));

      awaitUntil(
          () -> repository.findById(COMPLETED).orElseThrow().getEndedAt() != null || hasIncidents(application,
              COMPLETED),
          "the workflow of version 1 did not end");
      assertEquals(java.util.List.of(), incidents(application, COMPLETED), "no incident on the way");
      final var completed = repository.findById(COMPLETED).orElseThrow();
      assertTrue(completed.isCalculated(), "the service task after the user task ran");
      assertEquals(
          "0/2,1/2",
          completed.getIterations(),
          "each run of the called process was told the iteration of its caller");
      assertEquals("UFV_End", completed.getEndedAt(), "the application heard the end");
      assertNull(instanceOf(application, COMPLETED), "the workflow is over");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(4)
  @DisplayName("A user task of a workflow version 1 started is reported as canceled under version 2")
  public void aUserTaskOfVersionOneIsCanceled() throws Exception {

    final var application = boot();
    try {
      final var repository = application.getBean(UpgradeFromV1Repository.class);
      assertNotNull(reviewOf(application, CANCELED), "the second workflow still waits at its user task");
      application
          .getBean(TransactionTemplate.class)
          .executeWithoutResult(status -> application
              .getBean(UpgradeFromV1WorkflowService.class)
              .cancelReview(CANCELED));

      awaitUntil(
          () -> repository.findById(CANCELED).orElseThrow().getEndedAt() != null || hasIncidents(application, CANCELED),
          "the canceled workflow of version 1 did not end");
      assertEquals(java.util.List.of(), incidents(application, CANCELED), "no incident on the way");
      final var canceled = repository.findById(CANCELED).orElseThrow();
      assertEquals("CANCELED", canceled.getReviewEvents(), "the user task was reported as canceled");
      assertEquals("UFV_CanceledEnd", canceled.getEndedAt(), "the application heard the end");
    } finally {
      application.close();
    }

  }

  private static UpgradeFromV1Aggregate aggregate(
      final String id) {

    final var aggregate = new UpgradeFromV1Aggregate();
    aggregate.setId(id);
    return aggregate;

  }

  private static org.camunda.bpm.engine.runtime.ProcessInstance instanceOf(
      final ConfigurableApplicationContext application,
      final String businessKey) {

    return application
        .getBean(RuntimeService.class)
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .processDefinitionKey("UpgradeFromV1Process")
        .singleResult();

  }

  private static String reviewOf(
      final ConfigurableApplicationContext application,
      final String businessKey) {

    final var task = application
        .getBean(ProcessEngine.class)
        .getTaskService()
        .createTaskQuery()
        .processInstanceBusinessKey(businessKey)
        .taskDefinitionKey("UFV_Review")
        .singleResult();
    return task == null
        ? null
        : task.getId();

  }

  private static boolean hasIncidents(
      final ConfigurableApplicationContext application,
      final String businessKey) {

    return !incidents(application, businessKey).isEmpty();

  }

  /**
   * The incidents of one workflow, of its called instances included. A called instance goes
   * by the business key of its caller.
   */
  private static java.util.List<String> incidents(
      final ConfigurableApplicationContext application,
      final String businessKey) {

    final var runtime = application.getBean(RuntimeService.class);
    return runtime
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .list()
        .stream()
        .flatMap(instance -> runtime.createIncidentQuery().processInstanceId(instance.getId()).list().stream())
        .map(incident -> incident.getActivityId()
            + ": "
            + incident.getIncidentMessage())
        .toList();

  }

  /**
   * Waits for something which the job executor of the engine has to bring about. The
   * deadline is generous on purpose. In a full build this class shares its machine with the
   * other engines of this module.
   */
  private static void awaitUntil(
      final java.util.function.BooleanSupplier condition,
      final String whatDidNotHappen) throws Exception {

    final var deadline = System.currentTimeMillis() + 60_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(whatDidNotHappen);
      }
      Thread.sleep(100);
    }

  }

  private static ConfigurableApplicationContext boot() {

    final var boot = new ArrayList<String>();
    boot.add("--spring.datasource.url="
        + JDBC_URL);
    boot.add("--spring.profiles.active=upgrade-from-v1");
    boot.add("--spring.jpa.hibernate.ddl-auto=update");
    boot.add("--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/upgrade-from-v1");
    for (final var process : new String[]{
        "UpgradeFromV1Process", "UpgradeFromV1Child"
    }) {
      boot.add("--vanillabp.workflow-modules.c7-it.workflows.%s.allow-full-sync-with-bpms=true".formatted(process));
    }
    return new SpringApplicationBuilder(TestApplication.class).run(boot.toArray(String[]::new));

  }

}
