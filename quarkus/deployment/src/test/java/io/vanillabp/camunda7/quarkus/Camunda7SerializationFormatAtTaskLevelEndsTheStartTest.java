package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * <code>serialization-format</code> is not read at task level. A value set there ends the start,
 * and the message names the key and the levels where it works. Quarkus binds the key at task
 * level, so it is the adapter which ends the start, not Quarkus.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SerializationFormatAtTaskLevelEndsTheStartTest {

  private static final String TASK_LEVEL_KEY = "vanillabp.workflow-modules.c7-test.workflows.TestProcess.tasks.archiveTheOrder.adapters.c7.serialization-format";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("listeners/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey(TASK_LEVEL_KEY, "application/json")
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains(
                "Camunda 7 adapter 'c7' has 'serialization-format' set at TASK level, where nothing reads it"),
            () -> "expected the guiding startup failure but got:\n"
                + message);
        Assertions.assertTrue(message.contains(TASK_LEVEL_KEY), message);
        Assertions.assertTrue(
            message.contains("vanillabp.workflow-modules.<m>.workflows.<w>.adapters.c7.serialization-format"),
            message);
        Assertions.assertTrue(message.contains("Move the value to one of these levels, or remove it."), message);
      });

  @Test
  public void aFormatAtTaskLevelEndsTheStart() {
    // never runs, because the start fails as expected
  }

}
