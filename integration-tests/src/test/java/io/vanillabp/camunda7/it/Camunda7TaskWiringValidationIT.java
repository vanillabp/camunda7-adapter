package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Guiding wiring validation on a real engine boot (story 21b): a BPMN task whose
 * expression matches no {@code @WorkflowTask} method aborts the boot naming the
 * task and the fix.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TaskWiringValidationIT {

  @Test
  @DisplayName("A BPMN task without matching @WorkflowTask method aborts the boot with guiding messages")
  public void incompleteWiringAbortsBoot() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            // command-line args take precedence over the module's application.yaml
            .run(
                // the broken BPMN redefines 'TaskProcess' with an unimplemented task
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/broken",
                "--spring.datasource.url=jdbc:h2:mem:c7-wiring-validation;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(
        message.contains("Task wiring of BPMN process 'TaskProcess' of workflow module 'c7-it' is incomplete!"),
        "unexpected message: "
            + message);
    assertTrue(message.contains("'BR_Task'"), "unexpected message: "
        + message);
    assertTrue(
        message.contains("@WorkflowTask(taskDefinition = \"notImplemented\")"),
        "unexpected message: "
            + message);
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
