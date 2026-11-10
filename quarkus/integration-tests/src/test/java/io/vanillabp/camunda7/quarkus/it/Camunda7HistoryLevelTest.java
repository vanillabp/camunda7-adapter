package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Quarkus application whose engine writes another history level than the default
 * <code>full</code>. This test adds the Quarkus wiring: the runtime configuration key reaches
 * the engine. What an unknown level and a level which differs from the database do is the same
 * on both platforms, and the core proves it ({@code Camunda7HistoryLevelTest}). The same
 * application without the key is {@code Camunda7WorkflowLifecycleTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7HistoryLevelTest {

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
          .addAsResource("c7-e2e/processes/message-time-to-live.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. See this module's POM for the
      // systemPropertyVariables which feed 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(OneFreePortPerJvm.getPort()),
          "quarkus.log.file.enable", "true",
          "quarkus.log.file.path", Path
              .of("target", "c7-history-level-application.log")
              .toAbsolutePath()
              .toString(),
          "vanillabp.adapters.c7.history-level", "activity"));

  @Test
  @DisplayName("the key sets the history level of the engine, and the engine stores it")
  public void theKeyReachesTheEngine() {

    final Map<String, Object> reported = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(OneFreePortPerJvm.getPort())
        .get("introspect/engine/history-and-ids")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getMap("$");

    assertEquals("activity", reported.get("historyLevel"), () -> reported.toString());
    assertEquals("1", reported.get("storedHistoryLevel"), () -> reported.toString());

  }

}
