package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.FreePortUtil;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Quarkus application whose Camunda 7 engine was allowed to sleep, measured at its
 * datasource: while nothing is due, not one connection is taken. That is the claim this
 * feature lives by, and it is the only one a cloud bill can read.
 * <p>
 * Version 1 had no Quarkus artifact at all, so this is the half which did not exist. What
 * is Quarkus-bound here is the engine's own job executor, the JTA transaction the wake-up
 * hangs off and the runtime configuration key; the waiting rule itself is the core's, and
 * {@code Camunda7DueDateSleepTest} holds it against a real engine.
 * <p>
 * Two things have to be quiet for the measurement to mean anything, and both are named in
 * the startup message of the feature. VanillaBP's phase-two outbox polls its store, which is
 * turned down to ten minutes here, and the engine's own metrics reporter writes every 900
 * seconds, which the feature switches off by itself. Agroal's background validation is off
 * as well, because a pool checking its own connections is not the engine talking.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7SleepingEngineTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda7.quarkus.test")
          .addAsResource("application.yaml")
          .addAsResource("c7-e2e/processes/task-matrix.bpmn")
          .addAsResource("c7-e2e/processes/signal-catch.bpmn")
          .addAsResource("c7-e2e/processes/aggregate-changed.bpmn")
          .addAsResource("c7-e2e/processes/timer-start.bpmn")
          .addAsResource("c7-e2e/processes/versioned-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage - check this module's POM for the
      // systemPropertyVariables feeding 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(FreePortUtil.getFreePort()),
          "quarkus.log.file.enable", "true",
          "quarkus.log.file.path", Path
              .of("target", "c7-sleeping-engine-application.log")
              .toAbsolutePath()
              .toString(),
          "vanillabp.adapters.c7.sleep-until-something-is-due", "true",
          // the outbox is the other poller of a quiet application, and a measurement of the
          // engine's silence has to be able to tell the two apart
          "vanillabp.outbox.poll-interval", "PT10M",
          "quarkus.datasource.jdbc.background-validation-interval", "0"));

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

  private static String plainText(
      final String path) {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .asString();

  }

  private static long connectionsTaken() {

    return Long.parseLong(plainText("introspect/datasource/connections-taken"));

  }

  @Test
  @DisplayName("the property puts the sleeping acquisition into the application's engine")
  public void theApplicationGetsTheSleepingExecutor() {

    assertEquals("Camunda7SleepingJobExecutor", plainText("introspect/engine/job-executor"));

  }

  @Test
  @DisplayName("no connection is taken from the database while nothing is due")
  public void nothingIsAskedOfTheDatabaseWhileNothingIsDue() throws Exception {

    // asking costs whatever it costs, so the measurement subtracts itself: two readings
    // back to back say what one of them is worth
    final var firstReading = connectionsTaken();
    final var costOfAReading = connectionsTaken() - firstReading;

    waitUntilTheApplicationIsQuiet(costOfAReading);

    final var before = connectionsTaken();
    Thread.sleep(QUIET_WINDOW);
    final var after = connectionsTaken();

    assertEquals(
        0,
        after - before - costOfAReading,
        "a sleeping engine must not take a single connection while nothing is due");

  }

  /**
   * Waits until nothing but the readings themselves takes a connection any more. What boots
   * here writes for a while - the deployment, the workflow the module's timer start event
   * begins and its outbox entry - and the measurement only begins once all of it is done.
   */
  private void waitUntilTheApplicationIsQuiet(
      final long costOfAReading) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    var lastSeen = -1L;
    var quiet = false;
    while (!quiet) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the application never stopped taking connections");
      final var seen = connectionsTaken();
      quiet = (lastSeen >= 0) && ((seen - lastSeen) == costOfAReading);
      lastSeen = seen;
      Thread.sleep(1500);
    }

  }

}
