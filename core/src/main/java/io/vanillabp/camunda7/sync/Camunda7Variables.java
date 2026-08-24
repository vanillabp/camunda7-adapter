package io.vanillabp.camunda7.sync;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.camunda.bpm.engine.variable.Variables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the values a workflow aggregate shares with the BPMS into Camunda 7 process
 * variables.
 * <p>
 * The core hands over a normalized shape already: scalars, {@link Map}s of nested types
 * and {@link Collection}s of them, where everything the engine cannot compare has been
 * turned into its string form by the sync model. What is left is how the two kinds reach
 * the engine:
 * <ul>
 * <li><b>a scalar stays a scalar</b>, so a BPMN condition can compare it
 * (<code>${amount &gt; 1000}</code>) and Cockpit shows a value instead of a document.
 * Types Camunda 7 has no variable type for are mapped to the closest one it has:
 * <code>Float</code>, <code>BigDecimal</code> and <code>BigInteger</code> become doubles,
 * because a model comparing them means arithmetic, and a <code>Character</code> becomes a
 * string;</li>
 * <li><b>a nested structure becomes an object variable</b> in the configured
 * serialization format (see {@link Camunda7SerializationFormats}), which is what keeps
 * dot-notated expressions working: the engine deserializes the variable and EL navigates
 * it, so <code>${order.customer.name}</code> reads what it says. The format is the
 * application's choice - <code>application/xstream</code>
 * (<a href="https://github.com/RasPelikan/camunda-xstream">camunda-xstream</a>),
 * <code>application/json</code> (SPIN) or whatever dataformat it registers - and the
 * adapter only passes it on.</li>
 * </ul>
 * Without a configured format the engine falls back to Java serialization: readable to
 * nobody in Cockpit, and the engine's database then holds the application's class
 * versions. That is worth exactly one warning, which this class logs the first time it
 * writes such a value.
 */
public final class Camunda7Variables {

  private static final Logger log = LoggerFactory.getLogger(Camunda7Variables.class);

  /**
   * The missing-format warning is logged once per JVM: it names a configuration gap, and
   * one line per variable would bury it.
   */
  private static final AtomicBoolean MISSING_FORMAT_REPORTED = new AtomicBoolean();

  private Camunda7Variables() {
  }

  /**
   * Converts the shared values into process variables.
   *
   * @param sharedValues What the aggregate shares (may be empty)
   * @param serializationFormat The format nested values are stored in, or
   *          <code>null</code> to leave that to the engine's default
   * @return The variables, in the order given
   */
  public static Map<String, Object> of(
      final Map<String, Object> sharedValues,
      final String serializationFormat) {

    final var variables = new LinkedHashMap<String, Object>();
    if (sharedValues == null) {
      return variables;
    }
    // a plain put, NOT Map.of: a shared attribute may well be null and the engine
    // stores a null variable just fine
    sharedValues.forEach((
        name,
        value) -> variables.put(name, variableValueOf(name, value, serializationFormat)));
    return variables;

  }

  /**
   * One value as the engine stores it best.
   */
  private static Object variableValueOf(
      final String name,
      final Object value,
      final String serializationFormat) {

    if ((value instanceof Map<?, ?>) || (value instanceof Collection<?>)) {
      return objectValue(name, value, serializationFormat);
    }
    if ((value instanceof java.math.BigDecimal) || (value instanceof java.math.BigInteger) || (value instanceof Float)) {
      // Camunda 7 knows short, integer, long and double - everything else numeric
      // would become an object variable, and a model comparing a number means
      // arithmetic rather than text
      return ((Number) value).doubleValue();
    }
    if (value instanceof Character) {
      return String.valueOf(value);
    }
    return value;

  }

  /**
   * A nested value as an object variable of the configured format.
   */
  private static Object objectValue(
      final String name,
      final Object value,
      final String serializationFormat) {

    if ((serializationFormat == null) || serializationFormat.isBlank()) {
      reportMissingFormat(name);
      // no format configured: the engine's own default decides (which an application
      // may well have set on the engine itself)
      return Variables
          .objectValue(value)
          .create();
    }
    return Variables
        .objectValue(value)
        .serializationDataFormat(serializationFormat)
        .create();

  }

  /**
   * Says once that nested shared values are stored in whatever the engine defaults to.
   */
  private static void reportMissingFormat(
      final String name) {

    if (!MISSING_FORMAT_REPORTED.compareAndSet(false, true)) {
      return;
    }
    log.warn(
        """
            The workflow aggregate shares the nested value '{}', but no serialization format is \
            configured for it - the engine stores it in whatever its \
            'defaultSerializationFormat' says, which without a dataformat plugin is JAVA \
            serialization: unreadable in Cockpit, and the engine's database then depends on your \
            class versions. Configure a format VanillaBP passes to the engine, per workflow, per \
            workflow module or per adapter:
            vanillabp.adapters.<id>.serialization-format: application/xstream
            and put the matching dataformat on the classpath (e.g. camunda-xstream for \
            'application/xstream', the SPIN JSON dataformat for 'application/json'). A format also \
            keeps dot-notated expressions working, since the engine deserializes the variable \
            before evaluating '{}.something'.""",
        name,
        name);

  }

}
