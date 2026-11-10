package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A Quarkus application whose engine does not start, because the history level is not one the
 * engine knows. The adapter explains that and names the key. The engine producer throws this
 * explanation as it is, so it is the first message of the adapter the developer reads, and
 * not the cause of a general message about building the engine. What the explanation says is
 * proven in the core, by {@code Camunda7HistoryLevelTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7StartupExplanationTest {

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
      .setExpectExit(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(OneFreePortPerJvm.getPort()),
          "vanillabp.adapters.c7.history-level", "everything"));

  @Test
  @DisplayName("the first message of the adapter is its explanation, not the general message about building the engine")
  public void theExplanationComesFirst() {

    final var printed = prodModeTest.getStartupConsoleOutput();
    final var firstMessageOfTheAdapter = printed.indexOf("Camunda 7 adapter 'c7'");
    assertTrue(firstMessageOfTheAdapter >= 0, printed);
    assertTrue(
        printed
            .substring(firstMessageOfTheAdapter)
            .startsWith("Camunda 7 adapter 'c7' could not start its engine, because "
                + "'vanillabp.adapters.c7.history-level' is 'everything'"),
        printed);
    assertTrue(prodModeTest.getExitCode() != 0, printed);

  }

}
