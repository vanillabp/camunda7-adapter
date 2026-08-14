package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Deferred job-executor activation end to end (story 26e): asynchronous continuations
 * ({@code asyncBefore} jobs) are executed ONLY while workflow processing is started.
 * The test pauses processing via the engine holder ({@code stopWorkflowProcessing} of
 * the only module stops the engine-global executor), creates an instance whose
 * async-before job therefore stays pending, and resumes processing - the job runs and
 * the instance completes. This proves both directions of the lifecycle wired by the
 * deployment pipeline.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: test contexts are cached and live in parallel - another
    // context's engine (and job executor) on the same H2 database would execute
    // this test's deliberately-pending jobs
    "spring.datasource.url=jdbc:h2:mem:c7-lifecycle-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7JobExecutorLifecycleIT {

  private static final String MODULE_ID = "c7-it";

  @Autowired
  private Camunda7EngineHolder engineHolder;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private ProcessService<TestAggregate> processService;

  private long countInstances(
      final String businessKey) {

    return engineHolder
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .tenantIdIn(MODULE_ID)
        .count();

  }

  @Test
  @DisplayName("async jobs run only while workflow processing is started")
  public void jobsRunOnlyWhileProcessingIsStarted() throws Exception {

    // the deployment pipeline started processing at boot
    assertTrue(engineHolder.isJobExecutorActive(), "processing was started at boot");

    // pause processing: the only module stops -> the engine-global executor stops
    engineHolder.stopWorkflowProcessing(MODULE_ID);
    assertFalse(engineHolder.isJobExecutorActive());

    // an instance started now parks in its async-before job...
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("job-executor-lifecycle");
      return processService.startWorkflow(aggregate).getId();
    });
    assertNotNull(aggregateId);
    final var businessKey = String.valueOf(aggregateId);

    // ...and stays pending while the executor is stopped (completing would end the
    // instance - the trivial ${true} service task is the only wait state)
    Thread.sleep(1000);
    assertEquals(
        1,
        countInstances(businessKey),
        "the async-before job must not be executed while processing is stopped");

    // resuming processing lets the job executor pick the job up: the service task
    // completes and the instance ends
    engineHolder.startWorkflowProcessing(MODULE_ID);
    assertTrue(engineHolder.isJobExecutorActive());

    final var deadline = System.currentTimeMillis() + 15000;
    while (countInstances(businessKey) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the pending job was not executed after processing was started");
      Thread.sleep(100);
    }

  }

}
