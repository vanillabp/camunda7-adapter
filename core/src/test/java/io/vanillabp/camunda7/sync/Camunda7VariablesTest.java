package io.vanillabp.camunda7.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.variable.value.ObjectValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the values a workflow aggregate shares become Camunda 7 variables - a
 * scalar stays comparable, a nested structure becomes an object variable in the
 * configured serialization format (which is what keeps dot-notated expressions working).
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7VariablesTest {

  @Test
  @DisplayName("Scalars stay scalars, including null")
  public void scalarsStayScalars() {

    final var shared = new LinkedHashMap<String, Object>();
    shared.put("name", "ACME");
    shared.put("count", 3);
    shared.put("total", 4711L);
    shared.put("rate", 0.5d);
    shared.put("approved", Boolean.TRUE);
    shared.put("nothing", null);

    final var variables = Camunda7Variables.of(shared, "application/xstream");

    assertEquals("ACME", variables.get("name"));
    assertEquals(3, variables.get("count"));
    assertEquals(4711L, variables.get("total"));
    assertEquals(0.5d, variables.get("rate"));
    assertEquals(Boolean.TRUE, variables.get("approved"));
    assertTrue(variables.containsKey("nothing"), "a shared attribute may well be null");
    assertNull(variables.get("nothing"));

  }

  @Test
  @DisplayName("Numbers Camunda 7 has no type for become doubles, a character becomes a string")
  public void numbersWithoutATypeBecomeDoubles() {

    final var variables = Camunda7Variables
        .of(
            Map
                .of(
                    "amount", new BigDecimal("19.99"),
                    "huge", new BigInteger("42"),
                    "ratio", Float.valueOf(1.5f),
                    "grade", Character.valueOf('A')),
            "application/xstream");

    // a model comparing a number means arithmetic - as text '19.99' would not compare
    assertEquals(19.99d, variables.get("amount"));
    assertEquals(42.0d, variables.get("huge"));
    assertEquals(1.5d, variables.get("ratio"));
    assertEquals("A", variables.get("grade"));

  }

  @Test
  @DisplayName("A nested structure becomes an object variable of the configured format")
  public void nestedValuesBecomeObjectVariables() {

    final var customer = new LinkedHashMap<String, Object>();
    customer.put("name", "ACME");
    customer.put("vip", Boolean.TRUE);

    final var variables = Camunda7Variables
        .of(
            Map.of("customer", customer, "items", List.of("first", "second")),
            "application/xstream");

    final var customerVariable = assertInstanceOf(ObjectValue.class, variables.get("customer"));
    assertEquals("application/xstream", customerVariable.getSerializationDataFormat());
    assertEquals(customer, customerVariable.getValue());

    final var itemsVariable = assertInstanceOf(ObjectValue.class, variables.get("items"));
    assertEquals("application/xstream", itemsVariable.getSerializationDataFormat());
    assertEquals(List.of("first", "second"), itemsVariable.getValue());

  }

  @Test
  @DisplayName("Without a configured format the engine's default decides")
  public void withoutAFormatTheEngineDecides() {

    final var variables = Camunda7Variables.of(Map.of("customer", Map.of("name", "ACME")), null);

    final var customerVariable = assertInstanceOf(ObjectValue.class, variables.get("customer"));
    // no format named here: whatever 'defaultSerializationFormat' says applies, which is
    // exactly what the adapter warns about once
    assertNull(customerVariable.getSerializationDataFormat());

  }

  @Test
  @DisplayName("No values, no variables")
  public void nothingSharedIsNoVariable() {

    assertEquals(Map.of(), Camunda7Variables.of(Map.of(), "application/xstream"));
    assertEquals(Map.of(), Camunda7Variables.of(null, "application/xstream"));

  }

}
