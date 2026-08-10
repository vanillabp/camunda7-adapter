package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * THE critical assertion of the Camunda 7 adapter on Quarkus (story 26f): the
 * embedded engine joins the caller's JTA transaction (the {@code REQUIRED} semantics
 * of the engine's {@code JakartaTransactionInterceptor} on the shared Agroal
 * datasource) - a workflow started inside a {@code UserTransaction} is visible
 * within the transaction (business key = aggregate id, tenant = workflow module id)
 * and is committed or ROLLED BACK together with the JPA workflow aggregate. This is
 * the phase-one guarantee proven by the plain-engine analysis probe, now asserted
 * through the VanillaBP user API.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TransactionTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TestWorkflowService workflowService;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  private long countInstances(
      final String businessKey) {

    return engineRegistry
        .engineFor("c7")
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processDefinitionKey(BPMN_PROCESS_ID)
        .processInstanceBusinessKey(businessKey)
        .tenantIdIn(MODULE_ID)
        .count();

  }

  @Test
  public void startJoinsTheCallersJtaTransaction() throws Exception {

    // pause job processing so committed instances stay observable (the trivial
    // ${true} service task would complete them asynchronously)
    final var engine = engineRegistry.engineFor("c7");
    engine.stopWorkflowProcessing(MODULE_ID);
    try {

      // committed start: the instance is visible WITHIN the transaction and
      // persists after the commit
      userTransaction.begin();
      final TestAggregate committedAggregate;
      try {
        committedAggregate = workflowService.startWorkflow("commit-test");
        Assertions.assertNotNull(committedAggregate.getId());
        Assertions.assertEquals(
            1,
            countInstances(String.valueOf(committedAggregate.getId())),
            "the process instance is visible within the starting transaction");
      } catch (final Exception e) {
        userTransaction.rollback();
        throw e;
      }
      userTransaction.commit();
      Assertions.assertEquals(1, countInstances(String.valueOf(committedAggregate.getId())));

      // rolled-back start: aggregate AND process instance are gone - the
      // embedded-engine phase-one guarantee
      userTransaction.begin();
      final var rolledBackAggregate = workflowService.startWorkflow("rollback-test");
      final var rolledBackId = rolledBackAggregate.getId();
      Assertions.assertNotNull(rolledBackId);
      Assertions.assertEquals(
          1,
          countInstances(String.valueOf(rolledBackId)),
          "the process instance is visible within the transaction before rolling back");
      userTransaction.rollback();

      // reading the aggregate needs an active transaction/persistence context
      userTransaction.begin();
      try {
        Assertions.assertNull(
            entityManager.find(TestAggregate.class, rolledBackId),
            "the aggregate was rolled back with the transaction");
      } finally {
        userTransaction.rollback();
      }
      Assertions.assertEquals(
          0,
          countInstances(String.valueOf(rolledBackId)),
          "the process instance was rolled back with the transaction");

    } finally {
      engine.startWorkflowProcessing(MODULE_ID);
    }

  }

}
