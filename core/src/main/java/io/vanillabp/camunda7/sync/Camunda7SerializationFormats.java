package io.vanillabp.camunda7.sync;

/**
 * Which serialization format Camunda 7 stores a NESTED shared value in,
 * resolved per workflow with a fallback to the workflow module and to the adapter:
 *
 * <pre>
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;id&gt;.serialization-format
 * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.serialization-format
 * vanillabp.adapters.&lt;id&gt;.serialization-format
 * </pre>
 *
 * The value is a Camunda serialization data format, e.g.
 * <code>application/xstream</code> for
 * <a href="https://github.com/RasPelikan/camunda-xstream">camunda-xstream</a> or
 * <code>application/json</code> for the SPIN JSON dataformat. The engine needs the
 * matching dataformat on its classpath - which is the application's dependency, and the
 * adapter-level value is additionally applied to the engine's
 * <code>defaultSerializationFormat</code>, so an application configures the format once
 * and nowhere else.
 * <p>
 * Why it matters: a nested value stored in a readable format keeps DOT-NOTATED
 * expressions working (<code>${order.customer.name}</code>), because the engine
 * deserializes the variable before EL navigates it. Without any format the engine falls
 * back to Java serialization, which shows a blob in Cockpit and ties the engine's database
 * to the application's class versions - so the adapter says so, once, when it writes such
 * a value.
 * <p>
 * Implemented by the platform integrations, since each of them binds its own
 * configuration; the resolution across the three levels is
 * {@link #firstConfigured(String...)}.
 * <p>
 * Why a nested value is written in the engine's own format instead of as a JSON string is decision
 * 9 in the repository's DECISIONS.md.
 */
public interface Camunda7SerializationFormats {

  /**
   * The format for nested values of the given workflow.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The format or <code>null</code> where none is configured
   */
  String formatFor(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The first value which is configured, most specific first - the resolution every
   * platform implementation needs and none should re-invent.
   *
   * @param candidates The candidates, most specific first
   * @return The first non-blank value or <code>null</code>
   */
  static String firstConfigured(
      final String... candidates) {

    if (candidates == null) {
      return null;
    }
    for (final var candidate : candidates) {
      if ((candidate != null) && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;

  }

}
