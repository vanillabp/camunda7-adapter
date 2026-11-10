package io.vanillabp.camunda7.springboot.diagnostics;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.BeanCreationException;

import io.vanillabp.camunda7.engine.Camunda7StartupExplanation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The analyzer finds the adapter's explanation anywhere in the chain of causes and hands its
 * two parts to Spring Boot's report. That the report really shows them first is proven with a
 * booted application, in {@code Camunda7VersionOneDatabaseIT}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StartupExplanationFailureAnalyzerTest {

  private final Camunda7StartupExplanationFailureAnalyzer analyzer = new Camunda7StartupExplanationFailureAnalyzer();

  @Test
  @DisplayName("an explanation deep in bean creation exceptions becomes description and action")
  public void theExplanationIsFoundInTheChain() {

    final var engineFailure = new IllegalStateException("historyLevel mismatch");
    final var explanation = new Camunda7StartupExplanation(
        "Camunda 7 adapter 'c7' could not start its engine.", "Set the key.", engineFailure);
    final var failure = new BeanCreationException(
        "Camunda7_ProcessService_c7", "Error creating bean", new BeanCreationException(
            "Camunda7_Engine_c7", "Error creating bean", explanation));

    final var analysis = analyzer.analyze(failure);

    Assertions.assertEquals("Camunda 7 adapter 'c7' could not start its engine.", analysis.getDescription());
    Assertions.assertEquals("Set the key.", analysis.getAction());
    Assertions.assertSame(explanation, analysis.getCause());
    Assertions.assertEquals(
        "Camunda 7 adapter 'c7' could not start its engine. Set the key.",
        explanation.getMessage());

  }

  @Test
  @DisplayName("a failure without an explanation is left to the other analyzers")
  public void otherFailuresAreNotAnalyzed() {

    Assertions.assertNull(analyzer.analyze(
        new BeanCreationException("other", "Error creating bean", new IllegalStateException("something else"))));

  }

}
