package io.vanillabp.camunda7.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which identifiers the startup check has to ask the core about - the names a
 * model's conditions, timers and multi-instance collections read.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ExpressionIdentifiersTest {

  @Test
  @DisplayName("Conditions, timers and multi-instance collections are read, with their origin")
  public void identifiersOfAProcess() {

    final var model = Bpmn
        .readModelFromStream(
            getClass()
                .getClassLoader()
                .getResourceAsStream("sync/ExpressionsProcess.bpmn"));

    final var identifiers = Camunda7ExpressionIdentifiers.of(model, "ExpressionsProcess");

    assertTrue(identifiers.containsKey("approved"), identifiers.toString());
    assertTrue(identifiers.containsKey("customerName"), identifiers.toString());
    assertTrue(identifiers.containsKey("reminderDelay"), identifiers.toString());
    assertTrue(identifiers.containsKey("riskFactors"), identifiers.toString());

    // the sequence flow carries the id, its condition element does not
    assertEquals("Flow_yes", identifiers.get("approved").elementId());
    // the timer definition carries an id of its own, which is what a modeler finds
    assertEquals("Timer_1", identifiers.get("reminderDelay").elementId());
    assertEquals("Assess", identifiers.get("riskFactors").elementId());
    assertTrue(
        identifiers.get("approved").expression().contains("${approved and not empty customerName}"),
        identifiers.get("approved").expression());

    // the wired tasks are the EL resolver's business, not this check's
    assertFalse(identifiers.containsKey("approveTask"), "a task wired by expression is no variable");
    assertFalse(identifiers.containsKey("assessTask"), "a delegate expression is no variable");

  }

  @Test
  @DisplayName("An unknown process yields nothing")
  public void unknownProcessYieldsNothing() {

    final var model = Bpmn
        .readModelFromStream(
            getClass()
                .getClassLoader()
                .getResourceAsStream("sync/ExpressionsProcess.bpmn"));

    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.of(model, "NoSuchProcess").keySet());

  }

  @Test
  @DisplayName("A conditional event's condition is not collected, although the class says it is")
  public void aConditionalEventsConditionIsNotCollected() {

    final var model = Bpmn
        .readModelFromStream(
            getClass()
                .getClassLoader()
                .getResourceAsStream("sync/ConditionalEventProcess.bpmn"));

    // BEHAVIOUR UNDER EXAMINATION, NOT THE DESIRED ONE. The javadoc of the class under
    // test promises "conditions of sequence flows and conditional events", and the second
    // half of that sentence is not true: a conditional event carries its condition in a
    // 'Condition' element, which extends Expression, while a sequence flow carries a
    // 'ConditionExpression', which extends FormalExpression. The two are unrelated types,
    // so asking the model for one never answers the other and a model whose ONLY
    // expression is a conditional event yields nothing at all. Whoever fixes this deletes
    // this case and asserts the name instead.
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.of(model, "ConditionalEventProcess").keySet());

    // the name is a variable by every other measure, so nothing but the element type
    // keeps the check from seeing it
    assertEquals(
        Set.of("approvedByRiskOffice"),
        Camunda7ExpressionIdentifiers.identifiersOf("${approvedByRiskOffice}"));

  }

  @Test
  @DisplayName("Keywords, functions, namespaces and member reads are not variables")
  public void whatIsNoVariable() {

    assertEquals(Set.of("a", "b"), Camunda7ExpressionIdentifiers.identifiersOf("${a > 1 and b.c ne null}"));
    assertEquals(Set.of("order"), Camunda7ExpressionIdentifiers.identifiersOf("${order.customer.name}"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.identifiersOf("${empty null}"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.identifiersOf("${execution.getVariable('x')}"));
    assertEquals(Set.of("y"), Camunda7ExpressionIdentifiers.identifiersOf("${fn:format(y)}"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.identifiersOf("no expression at all"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.identifiersOf(null));
    // '#{...}' is an expression as well
    assertEquals(Set.of("approved"), Camunda7ExpressionIdentifiers.identifiersOf("#{approved}"));

  }

}
