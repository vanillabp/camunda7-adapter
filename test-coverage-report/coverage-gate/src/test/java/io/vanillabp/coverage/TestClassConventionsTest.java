package io.vanillabp.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.TestClassConventions;

/**
 * The gate of story 108, in the module which already gates this repository as a whole:
 * every test class registers {@link SuppressOutputExtension}, so a build log carries
 * what a FAILING test printed and nothing else.
 * <p>
 * Four classes of this repository did not, and all four were quiet at the time, which is
 * why nobody noticed. The rule had been written down twice before it drifted, so it is
 * checked here rather than reviewed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TestClassConventionsTest {

  @Test
  @DisplayName("Every test class of this repository suppresses its output")
  public void everyTestClassSuppressesItsOutput() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = TestClassConventions.testClassesWithoutOutputSuppression(root);

    assertTrue(
        offenders.isEmpty(),
        () -> TestClassConventions.describeTestClassesWithoutOutputSuppression(offenders));

  }

}
