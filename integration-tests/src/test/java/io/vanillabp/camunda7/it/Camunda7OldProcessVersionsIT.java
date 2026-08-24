package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The old-versions startup check against a REAL engine: the application deploys version
 * 1 of a process, leaves a workflow running on it, and boots again with a model which
 * dropped one of its tasks. What the application still serves of that older version is
 * what the engine can answer and this test proves.
 * <p>
 * Every case is a full boot, because the question is what a START reports, and the
 * findings are read from the captured output rather than from a log appender: Spring
 * Boot resets the logging context while it starts, which takes an appender attached
 * beforehand with it. The engine keeps its deployments in the database of this class,
 * so the boots build on each other and therefore run in order.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda7OldProcessVersionsIT {

  private static final String DATABASE = "--spring.datasource.url=jdbc:h2:mem:c7-old-process-versions;DB_CLOSE_DELAY=-1";

  @Test
  @Order(1)
  @DisplayName("Version 1 is deployed and a workflow is left running on it")
  public void deployVersionOneAndLeaveAWorkflowRunning() throws Exception {

    final var application = new SpringApplicationBuilder(TestApplication.class).run(DATABASE, resources("v1"));
    try {
      final var workflowService = application.getBean(OldProcessVersionsWorkflowService.class);
      final var repository = application.getBean(OldProcessVersionsRepository.class);
      final var aggregate = application
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .execute(status -> workflowService.startWorkflow());

      // the workflow walks to the task which stays open, and waits there for the
      // rest of this test class
      final var deadline = System.currentTimeMillis() + 30_000;
      while (repository.findById(aggregate.getId()).orElseThrow().getOpenTaskId() == null) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError("the workflow of version 1 did not reach its open task");
        }
        Thread.sleep(100);
      }
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("What version 1 still needs is reported, and the workflow running on it makes it an error")
  public void theUnservedTaskOfTheOldVersionIsReported(
      final CapturedOutput output) {

    final var reported = whatIsReportedWhileBooting(output, "v2");
    assertTrue(reported.contains("'servedForAnUnknownVersion'"), "the unserved task of version 1 is named");
    assertTrue(reported.contains("still run on version '1'"), "the workflow of version 1 is counted");
    assertTrue(reported.contains("OldProcessVersionsProcess"), "the process is named");
    assertTrue(reported.contains("outfaded-versions"), "the way out is named");
    // the method kept for version 1 serves its task, so that one is not demanded
    assertTrue(
        !reported.contains("definition(s) 'droppedInVersionTwo'"),
        "the task served by the version-1 method is not reported");
    // the method naming a version this engine never had never runs, and says so
    assertTrue(reported.contains("servedForAnUnknownVersion' (version '0')"), "the dead method is named");
    assertTrue(reported.contains("the method never runs"), "and what that means is said");

  }

  @Test
  @Order(3)
  @DisplayName("Fading version 1 out stops the demand and names the workflow left behind")
  public void fadingTheOldVersionOutChangesTheFinding(
      final CapturedOutput output) {

    final var reported = whatIsReportedWhileBooting(
        output,
        "v2",
        "--vanillabp.workflow-modules.c7-it.adapters.c7.outfaded-versions=<2");
    assertTrue(
        !reported.contains("served by NO @WorkflowTask method"),
        "an outfaded version is not checked for unserved tasks");
    assertTrue(reported.contains("still run on version '1'"), "the workflow left behind is reported");
    assertTrue(reported.contains("outfaded-versions-in-use"), "and how to make that stop the start");
    // the method kept for version 1 serves nothing once that version is faded out
    assertTrue(reported.contains("droppedInVersionTwo"), "the method for the faded-out version is named");
    assertTrue(reported.contains("faded out by"), "and the reason is the configuration");

  }

  @Test
  @Order(4)
  @DisplayName("With the policy set, the workflow left behind stops the start")
  public void theStartCanBeMadeToFail() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> boot(
            "v2",
            "--vanillabp.workflow-modules.c7-it.adapters.c7.outfaded-versions=<2",
            "--vanillabp.adapters.c7.outfaded-versions-in-use=FAIL"));

    assertTrue(rootMessage(failure).contains("still run on version '1'"), rootMessage(failure));

  }

  @Test
  @Order(5)
  @DisplayName("Fading out the version this boot deploys is a configuration error")
  public void fadingOutTheDeployedVersionFailsTheStart() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> boot("v2", "--vanillabp.workflow-modules.c7-it.adapters.c7.outfaded-versions=*"));

    assertTrue(rootMessage(failure).contains("deployed during this boot"), rootMessage(failure));

  }

  /**
   * What ONE boot wrote - the captured output accumulates over the whole class, so
   * every case looks at its own tail of it.
   */
  private static String whatIsReportedWhileBooting(
      final CapturedOutput output,
      final String version,
      final String... arguments) {

    final var before = output.getAll().length();
    boot(version, arguments);
    return output.getAll().substring(before);

  }

  private static void boot(
      final String version,
      final String... arguments) {

    final var boot = new String[arguments.length + 2];
    boot[0] = DATABASE;
    boot[1] = resources(version);
    System.arraycopy(arguments, 0, boot, 2, arguments.length);
    new SpringApplicationBuilder(TestApplication.class).run(boot).close();

  }

  private static String resources(
      final String version) {

    return "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/old-process-versions/%s"
        .formatted(version);

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
