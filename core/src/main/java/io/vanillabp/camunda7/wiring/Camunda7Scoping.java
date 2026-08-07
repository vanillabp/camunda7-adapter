package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.Error;
import org.camunda.bpm.model.bpmn.instance.Escalation;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.Signal;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@link NameClashAvoidance#USE_PREFIX} to a Camunda 7 model (story 35):
 * every identifier the ENGINE resolves across process definitions is prefixed, so
 * two workflow modules may use the same names without a Camunda tenant.
 *
 * <table>
 * <caption>What is rewritten - and what deliberately is not</caption>
 * <tr><th>Element</th><th>Rewritten?</th><th>Why</th></tr>
 * <tr><td>{@code bpmn:process id}</td><td>yes</td><td>the process definition key is engine-wide</td></tr>
 * <tr><td>{@code camunda:calledElement} of a call activity</td><td>yes</td><td>it addresses the renamed process</td></tr>
 * <tr><td>{@code bpmn:message name}</td><td>yes</td><td>message correlation resolves by name across definitions</td></tr>
 * <tr><td>{@code bpmn:signal name}, {@code bpmn:escalation escalationCode}</td><td>yes</td><td>broadcast by name</td></tr>
 * <tr><td>{@code bpmn:error errorCode}</td><td>yes</td><td>completeness with the other adapters - the application may raise it via {@code ProcessService#cancelTask}</td></tr>
 * <tr><td>task definitions ({@code camunda:expression}, {@code camunda:delegateExpression}, {@code camunda:formKey})</td><td><b>no</b></td><td>they are PROCESS-LOCAL in Camunda 7: the expression is evaluated inside the process by VanillaBP's EL resolver, nothing subscribes to them engine-wide. Camunda 8 job types are the opposite case and ARE prefixed.</td></tr>
 * </table>
 */
@Slf4j
public final class Camunda7Scoping {

  private Camunda7Scoping() {
  }

  /**
   * Whether the given workflow module's identifiers are prefixed for this adapter.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support (may be
   *          <code>null</code>)
   * @return Whether prefixing applies
   */
  public static boolean prefixes(
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    return (scoping != null) && (scoping.modeFor(workflowModuleId, null, adapterId) == NameClashAvoidance.USE_PREFIX);

  }

  /**
   * Rewrites the identifiers of the given model in place. A no-op unless the mode of
   * the workflow module is {@link NameClashAvoidance#USE_PREFIX}.
   *
   * @param model The model of one BPMN file
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support
   */
  public static void apply(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    if (!prefixes(workflowModuleId, adapterId, scoping)) {
      return;
    }

    model
        .getModelElementsByType(Message.class)
        .forEach(message -> message.setName(
            scoping.scopedIdentifier(workflowModuleId, message.getName(), adapterId)));
    model
        .getModelElementsByType(Signal.class)
        .forEach(signal -> signal.setName(
            scoping.scopedIdentifier(workflowModuleId, signal.getName(), adapterId)));
    model
        .getModelElementsByType(Escalation.class)
        .forEach(escalation -> escalation.setEscalationCode(
            scoping.scopedIdentifier(workflowModuleId, escalation.getEscalationCode(), adapterId)));
    model
        .getModelElementsByType(Error.class)
        .forEach(error -> error.setErrorCode(
            scoping.scopedIdentifier(workflowModuleId, error.getErrorCode(), adapterId)));

    // call activities address another process BY ID - rewrite before the ids change
    model
        .getModelElementsByType(CallActivity.class)
        .forEach(callActivity -> {
          final var calledElement = callActivity.getCalledElement();
          if ((calledElement == null) || calledElement.isBlank()) {
            return; // addressed by an expression - the application owns that string
          }
          callActivity.setCalledElement(
              scoping.scopedProcessId(workflowModuleId, calledElement, adapterId));
        });

    model
        .getModelElementsByType(Process.class)
        .forEach(process -> {
          final var scoped = scoping.scopedProcessId(workflowModuleId, process.getId(), adapterId);
          if (scoped.equals(process.getId())) {
            return;
          }
          log.debug(
              "Camunda7: BPMN process '{}' of workflow module '{}' is deployed as '{}' (name-clash "
                  + "avoidance 'use-prefix')",
              process.getId(),
              workflowModuleId,
              scoped);
          process.setId(scoped);
        });

  }

}
