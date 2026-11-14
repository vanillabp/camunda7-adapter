package io.vanillabp.camunda7.quarkus.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The cases of {@link Camunda7EngineExtensionKeys} which a boot of Quarkus does not show
 * cheaply: environment variables, several adapter ids, the name of a fork, and keys which only
 * look similar.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7EngineExtensionKeysEdgeCasesTest {

  @Test
  public void anEnvironmentVariableIsFoundAsWell() {

    final var failure = assertThrows(IllegalStateException.class, () -> Camunda7EngineExtensionKeys
        .endTheStartOnExtensionKeys("camunda", List.of("c7"), List.of("QUARKUS_CAMUNDA_GENERIC_CONFIG_HISTORY",
            "QUARKUS_CAMUNDA_DATASOURCE")));
    assertTrue(failure
        .getMessage()
        .contains("'QUARKUS_CAMUNDA_GENERIC_CONFIG_HISTORY': Write 'vanillabp.adapters.c7.history-level' instead."),
        failure::getMessage);
    assertTrue(failure
        .getMessage()
        .contains("'QUARKUS_CAMUNDA_DATASOURCE': Write 'vanillabp.adapters.c7.data-source-name' instead."),
        failure::getMessage);

  }

  @Test
  public void severalAdapterIdsAreNamedOnce() {

    final var failure = assertThrows(IllegalStateException.class, () -> Camunda7EngineExtensionKeys
        .endTheStartOnExtensionKeys("camunda", List.of("a", "b"), List.of("quarkus.camunda.generic-config"
            + ".history-time-to-live", "quarkus.camunda.something-else")));
    assertTrue(failure
        .getMessage()
        .contains("Write 'vanillabp.adapters.<id>.history-time-to-live' instead."), failure::getMessage);
    assertTrue(failure
        .getMessage()
        .contains("'quarkus.camunda.something-else': Nothing in VanillaBP reads this key. Remove it."),
        failure::getMessage);
    assertTrue(failure
        .getMessage()
        .contains("write it once for each Camunda 7 adapter id that needs it (a, b)."), failure::getMessage);

  }

  @Test
  public void aForkChecksTheKeysOfItsOwnName() {

    final var failure = assertThrows(IllegalStateException.class, () -> Camunda7EngineExtensionKeys
        .endTheStartOnExtensionKeys("operaton", List.of("op"), List.of("quarkus.operaton.generic-config"
            + ".\"database-schema-update\"", "quarkus.camunda.datasource")));
    assertTrue(failure
        .getMessage()
        .contains("Write 'vanillabp.adapters.op.database-schema-update' instead."), failure::getMessage);
    assertFalse(failure
        .getMessage()
        .contains("quarkus.camunda.datasource"), failure::getMessage);

  }

  @Test
  public void otherKeysAreLeftAlone() {

    assertDoesNotThrow(() -> Camunda7EngineExtensionKeys
        .endTheStartOnKeysOfTheEngineExtension(List.of("c7"), List.of("quarkus.camundafoo.x", "quarkus.datasource"
            + ".db-kind", "camunda.bpm.history-level", "vanillabp.adapters.c7.type")));

  }

}
