package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an idle engine costs its database, measured. Every connection the engine takes is
 * counted, so the claim this feature lives by can be stated as a number rather than as a
 * description of a sleep: while nothing is due, the count does not move.
 * <p>
 * The engine here is a plain standalone one on H2, built twice per comparison: once with
 * the acquisition which sleeps until something is due, once exactly as the engine ships.
 * The wait times are turned down to 100 ms so the difference between sleeping and polling
 * is visible in seconds rather than in minutes; the adapter leaves them at the engine's
 * defaults.
 * <p>
 * The platform halves prove the wiring instead, each on its own datasource counter
 * ({@code Camunda7SleepingJobExecutorIT} on Spring Boot and the Quarkus lifecycle test),
 * because a correct strategy says nothing about a platform ever installing it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7DueDateSleepTest {

  /**
   * How long the engine waits between two cycles while it polls. Small enough that a
   * polling engine and a sleeping one are told apart within a second or two.
   */
  private static final int POLLING_INTERVAL = 100;

  /**
   * The window the measurements watch. Long enough for a polling engine to come back
   * several times, short enough to keep this test class quick.
   */
  private static final long QUIET_WINDOW = 2000;

  /**
   * How long a test waits for a workflow which should have finished. Generous, because a
   * build machine running other builds is allowed to be slow; what the tests assert is
   * never the exact moment.
   */
  private static final long PATIENCE = 20000;

  private static final String SLEEPER = "ASleeper";

  private static final String WORKER = "AWorker";

  private ProcessEngine processEngine;

  private JobExecutor jobExecutor;

  private CountingConnections connections;

  @AfterEach
  public void closeTheEngine() {

    if (jobExecutor != null) {
      jobExecutor.shutdown();
    }
    if (processEngine != null) {
      processEngine.close();
    }

  }

  /**
   * An H2 datasource which counts every connection the engine takes. A connection is what
   * an engine command needs before it can say a word to the database, so a count which
   * does not move is a database which was not spoken to.
   */
  private static final class CountingConnections implements DataSource {

    private final JdbcDataSource h2 = new JdbcDataSource();

    private final AtomicInteger taken = new AtomicInteger();

    void setURL(
        final String url) {

      h2.setURL(url);

    }

    int count() {

      return taken.get();

    }

    @Override
    public Connection getConnection() throws SQLException {

      taken.incrementAndGet();
      return h2.getConnection();

    }

    @Override
    public Connection getConnection(
        final String username,
        final String password) throws SQLException {

      taken.incrementAndGet();
      return h2.getConnection(username, password);

    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {

      return h2.getLogWriter();

    }

    @Override
    public void setLogWriter(
        final PrintWriter out) throws SQLException {

      h2.setLogWriter(out);

    }

    @Override
    public void setLoginTimeout(
        final int seconds) throws SQLException {

      h2.setLoginTimeout(seconds);

    }

    @Override
    public int getLoginTimeout() throws SQLException {

      return h2.getLoginTimeout();

    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {

      return h2.getParentLogger();

    }

    @Override
    public <T> T unwrap(
        final Class<T> iface) throws SQLException {

      return h2.unwrap(iface);

    }

    @Override
    public boolean isWrapperFor(
        final Class<?> iface) throws SQLException {

      return h2.isWrapperFor(iface);

    }

  }

  /**
   * A workflow which waits in a timer for as long as the variable <code>duration</code>
   * says, plus one which has a job to do right away.
   */
  private static BpmnModelInstance aWorkflowWaitingInATimer() {

    return Bpmn
        .createExecutableProcess(SLEEPER)
        .startEvent()
        .intermediateCatchEvent()
        .timerWithDuration("${duration}")
        .endEvent()
        .done();

  }

  private static BpmnModelInstance aWorkflowWithSomethingToDoNow() {

    return Bpmn
        .createExecutableProcess(WORKER)
        .startEvent()
        .serviceTask()
        .camundaAsyncBefore()
        .camundaExpression("${true}")
        .endEvent()
        .done();

  }

  /**
   * Builds an engine on a counted H2 database and deploys both models.
   *
   * @param name The database and engine name, one per test
   * @param sleeps Whether the acquisition sleeps until something is due
   */
  private void anEngine(
      final String name,
      final boolean sleeps) {

    connections = new CountingConnections();
    connections.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));

    final var properties = new Camunda7EngineProperties();
    properties.setSleepUntilSomethingIsDue(sleeps);

    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setProcessEngineName(name);
    configuration.setDataSource(connections);
    configuration.setDatabaseSchemaUpdate("create-drop");
    configuration.setHistoryTimeToLive("P180D");
    configuration.setJobExecutorActivate(false);
    if (sleeps) {
      configuration.setJobExecutor(new Camunda7SleepingJobExecutor(name));
    }
    Camunda7JobExecutorSleep.applyTo(name, configuration, properties);

    processEngine = configuration.buildProcessEngine();
    jobExecutor = ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
        .getJobExecutor();
    jobExecutor.setWaitTimeInMillis(POLLING_INTERVAL);
    jobExecutor.setMaxWait(POLLING_INTERVAL);

    processEngine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("sleeper.bpmn", aWorkflowWaitingInATimer())
        .addModelInstance("worker.bpmn", aWorkflowWithSomethingToDoNow())
        .deploy();

  }

  private void startTheTimerOf(
      final String duration) {

    processEngine
        .getRuntimeService()
        .startProcessInstanceByKey(SLEEPER, Variables.putValue("duration", duration));

  }

  private long runningWorkflows() {

    return processEngine
        .getRuntimeService()
        .createProcessInstanceQuery()
        .count();

  }

  /**
   * Waits until no workflow is running any more, and says how long that took.
   */
  private long waitForEveryWorkflowToEnd() throws InterruptedException {

    final var started = System.currentTimeMillis();
    final var deadline = started + PATIENCE;
    while (runningWorkflows() > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "a workflow was still running after %d ms".formatted(Long.valueOf(PATIENCE)));
      Thread.sleep(50);
    }
    return System.currentTimeMillis() - started;

  }

  /**
   * Lets the acquisition run its first cycle, then reports how many connections it takes
   * while nothing is due.
   */
  private int connectionsTakenWhileNothingIsDue() throws InterruptedException {

    jobExecutor.start();
    // one cycle, so the measurement is about the waiting rather than about the start
    Thread.sleep(POLLING_INTERVAL * 3L);
    final var before = connections.count();
    Thread.sleep(QUIET_WINDOW);
    return connections.count() - before;

  }

  @Test
  @DisplayName("an engine with nothing due does not talk to its database at all")
  public void nothingIsAskedOfTheDatabaseWhileNothingIsDue() throws Exception {

    anEngine("c7-sleep-quiet", true);

    assertEquals(
        0,
        connectionsTakenWhileNothingIsDue(),
        "a sleeping acquisition must not take a single connection while nothing is due");

  }

  @Test
  @DisplayName("an engine which does not sleep keeps asking, which is today's behaviour")
  public void anEngineWhichDoesNotSleepKeepsAsking() throws Exception {

    anEngine("c7-sleep-off", false);

    assertTrue(
        connectionsTakenWhileNothingIsDue() > 1,
        "without the property the engine polls, which is what this feature is measured against");

  }

  @Test
  @DisplayName("a job due later runs at its due date, and nothing is asked until then")
  public void aJobDueLaterRunsAtItsDueDate() throws Exception {

    anEngine("c7-sleep-duedate", true);
    jobExecutor.start();
    Thread.sleep(POLLING_INTERVAL * 3L);

    // the engine hints its own executor only for a job due inside the executor's wait
    // time, so a timer further out is the case this feature has to cover itself
    final var before = connections.count();
    startTheTimerOf("PT2S");

    // nothing is asked of the engine while the timer runs, because every question of this
    // test would be counted as a connection as well
    Thread.sleep(1000);
    assertEquals(
        1,
        runningWorkflows(),
        "the timer is due in two seconds, so the workflow has to be waiting after one");
    Thread.sleep(2500);
    final var spent = connections.count() - before;
    assertEquals(0, runningWorkflows(), "the timer was due, so the workflow has to have ended");

    // what those 3.5 seconds may cost: the start, the cycle the commit woke, the cycle at
    // the due date, the job itself, the cycle after it, and the two questions above. A
    // polling engine would have come back thirty times instead
    assertTrue(
        spent < 15,
        "the acquisition took %d connections where it should have waited for the due date"
            .formatted(Integer.valueOf(spent)));

  }

  @Test
  @DisplayName("a commit shortens a sleep which was already running")
  public void aCommitShortensTheSleep() throws Exception {

    anEngine("c7-sleep-shortened", true);
    jobExecutor.start();

    // the acquisition is now asleep for half a minute
    startTheTimerOf("PT30S");
    Thread.sleep(POLLING_INTERVAL * 3L);

    // and this commit has to pull the wake-up forward to the nearer due date
    startTheTimerOf("PT1S");
    final var deadline = System.currentTimeMillis() + PATIENCE;
    while (runningWorkflows() > 1) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the nearer timer did not shorten the sleep of the longer one");
      Thread.sleep(50);
    }

    assertEquals(
        1,
        runningWorkflows(),
        "the workflow whose timer is due in half a minute has to be still waiting");

  }

  @Test
  @DisplayName("an engine with no job at all waits until something happens")
  public void anEngineWithoutAnyJobWaitsUntilSomethingHappens() throws Exception {

    anEngine("c7-sleep-nojob", true);

    assertEquals(
        0,
        connectionsTakenWhileNothingIsDue(),
        "an engine holding no job at all has nothing to wake up for");

    processEngine
        .getRuntimeService()
        .startProcessInstanceByKey(WORKER);
    final var waited = waitForEveryWorkflowToEnd();

    assertTrue(
        waited < PATIENCE,
        "the waiting acquisition has to be woken by the transaction which wrote the job");

  }

}
