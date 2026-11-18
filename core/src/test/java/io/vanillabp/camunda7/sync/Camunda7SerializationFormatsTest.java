package io.vanillabp.camunda7.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The resolution of the serialization format across its three levels, and the refusal of a
 * value set at task level, where nothing reads it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SerializationFormatsTest {

  @Test
  @DisplayName("The most specific configured format wins, and a blank one counts as unset")
  public void theMostSpecificConfiguredFormatWins() {

    assertEquals("application/json", Camunda7SerializationFormats.firstConfigured(null, " ", "application/json"));
    assertEquals(
        "application/xstream",
        Camunda7SerializationFormats.firstConfigured("application/xstream", "application/json"));
    assertNull(Camunda7SerializationFormats.firstConfigured((String[]) null));
    assertNull(Camunda7SerializationFormats.firstConfigured(null, ""));

  }

  @Test
  @DisplayName("A format at task level ends the start, and the message says where the key works")
  public void aFormatAtTaskLevelEndsTheStart() {

    Camunda7SerializationFormats.refuseKeysSetAtTaskLevel("c7", List.of());

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> Camunda7SerializationFormats
            .refuseKeysSetAtTaskLevel(
                "c7",
                List
                    .of(
                        "vanillabp.workflow-modules.loans.workflows.Loan.tasks.approve.adapters.c7.serialization-format",
                        "vanillabp.workflow-modules.loans.workflows.Loan.tasks.archive.adapters.c7.serialization-format")));

    final var message = failure.getMessage();
    assertTrue(
        message.contains("Camunda 7 adapter 'c7' has 'serialization-format' set at TASK level, where nothing reads it"),
        () -> "the defect: "
            + message);
    assertTrue(
        message.contains("tasks.approve.adapters.c7.serialization-format\n  "),
        () -> "each key the reader has to find in their configuration, one per line: "
            + message);
    assertTrue(message.contains("tasks.archive.adapters.c7.serialization-format"), message);
    assertTrue(message.contains("Move the value to one of these levels, or remove it."), message);
    assertTrue(
        message.contains("""
            vanillabp.adapters.c7.serialization-format
            vanillabp.workflow-modules.<m>.adapters.c7.serialization-format
            vanillabp.workflow-modules.<m>.workflows.<w>.adapters.c7.serialization-format"""),
        () -> "and the three levels where it works, ready to paste: "
            + message);
    assertTrue(message.contains("one workflow needs one format"), () -> "and why: "
        + message);

  }

}
