package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.engine.Camunda7SleepingSpringJobExecutor;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Spring Boot application whose Camunda 7 engine was allowed to sleep, measured at
 * its datasource: while nothing is due, not one connection is taken. That is the claim this
 * feature lives by, and it is the only one a cloud bill can read.
 * <p>
 * Two things have to be quiet for the measurement to mean anything, and both are named in
 * the startup message of the feature. VanillaBP's phase-two outbox polls its store, which is
 * turned down to ten minutes here, and the engine's own metrics reporter writes every 900
 * seconds, which the feature switches off by itself. What is left is the job acquisition,
 * and it sleeps.
 * <p>
 * The waiting rule itself is proven against a real engine in the core's
 * {@code Camunda7DueDateSleepTest}, including a job due later, a commit which shortens a
 * sleep and an engine holding no job at all. What this test adds is the wiring: that a
 * Spring Boot application reading the property really gets that engine.
 */
@SpringBootTest(classes = {
    TestApplication.class, CountedDataSourceConfiguration.class
}, properties = {
    "vanillabp.adapters.c7.sleep-until-something-is-due=true",
    // the outbox is the other poller of a quiet application, and a measurement of the
    // engine's silence has to be able to tell the two apart
    "vanillabp.outbox.poll-interval=PT10M"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a datasource (and therefore a context) of its
// own, and an engine outliving its test would keep working on a database the next classes use
@DirtiesContext
public class Camunda7SleepingEngineIT {

  /**
   * How long the database has to stay untouched. Longer than the engine's widest polling
   * interval, so a poller could not hide inside the window.
   */
  private static final long QUIET_WINDOW = 4000;

  /**
   * How long the test waits for the application to finish what booting left behind: the
   * deployment, the workflow the module's timer start event begins, and the outbox entry of
   * it.
   */
  private static final long PATIENCE = 60000;

  @Autowired
  private Camunda7EngineHolder engineHolder;

  @Test
  @DisplayName("the property puts the sleeping acquisition into the application's engine")
  public void theApplicationGetsTheSleepingExecutor() {

    final var jobExecutor = ((ProcessEngineConfigurationImpl) engineHolder
        .getProcessEngine()
        .getProcessEngineConfiguration()).getJobExecutor();

    assertInstanceOf(Camunda7SleepingSpringJobExecutor.class, jobExecutor);
    assertTrue(engineHolder.isJobExecutorActive(), "processing was started at boot");

  }

  @Test
  @DisplayName("no connection is taken from the database while nothing is due")
  public void nothingIsAskedOfTheDatabaseWhileNothingIsDue() throws Exception {

    waitUntilTheApplicationIsQuiet();

    final var before = CountedDataSourceConfiguration.connectionsTaken();
    Thread.sleep(QUIET_WINDOW);

    assertEquals(
        before,
        CountedDataSourceConfiguration.connectionsTaken(),
        "a sleeping engine must not take a single connection while nothing is due");

  }

  /**
   * Waits until nothing takes a connection any more. What boots here writes for a while -
   * the deployment, the workflow the module's timer start event begins and its outbox entry
   * - and the measurement only begins once all of it is done.
   */
  private void waitUntilTheApplicationIsQuiet() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    var lastSeen = -1;
    while (lastSeen != CountedDataSourceConfiguration.connectionsTaken()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the application never stopped taking connections");
      lastSeen = CountedDataSourceConfiguration.connectionsTaken();
      Thread.sleep(1500);
    }

  }

}
