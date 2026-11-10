package io.vanillabp.camunda7.springboot.diagnostics;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.Ordered;

import io.vanillabp.camunda7.engine.Camunda7StartupExplanation;

/**
 * Puts the adapter's own explanation of a failed start under "APPLICATION FAILED TO START".
 * <p>
 * The explanation is thrown while Spring builds a bean, so it ends up deep in a chain of
 * {@code BeanCreationException}s. Without this analyzer the developer reads that chain from
 * the outside in and finds the reason only at its end. The analyzer looks through the whole
 * chain and shows the explanation's two parts as "Description" and "Action".
 * <p>
 * Spring Boot finds the analyzer through <code>META-INF/spring.factories</code>. It runs
 * before Spring Boot's own analyzers. One of those could otherwise explain an outer bean
 * creation exception and hide the adapter's message again.
 */
public class Camunda7StartupExplanationFailureAnalyzer implements FailureAnalyzer, Ordered {

  /**
   * Spring Boot builds the analyzer when a start fails. It holds nothing.
   */
  public Camunda7StartupExplanationFailureAnalyzer() {

  }

  @Override
  public FailureAnalysis analyze(
      final Throwable failure) {

    final var explanation = Camunda7StartupExplanation.in(failure);
    if (explanation == null) {
      return null;
    }
    return new FailureAnalysis(explanation.getDescription(), explanation.getAction(), explanation);

  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

}
