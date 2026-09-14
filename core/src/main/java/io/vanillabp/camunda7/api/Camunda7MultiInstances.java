package io.vanillabp.camunda7.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;

/**
 * The multi-instance scopes an execution runs in, outermost first.
 * <p>
 * Camunda 7 keeps the item, the index and the total of a multi-instance activity in
 * variables of the executions the element hangs below, so the answer is found by walking
 * from the execution at hand up through its parents and its calling processes and collecting
 * every multi-instance activity on the way. That walk is the deepest piece of engine
 * knowledge in this repository and the one a Camunda upgrade is most likely to invalidate,
 * which is why it exists exactly once and is published here rather than written a second
 * time by whoever else needs it.
 *
 * <h2>What it promises</h2>
 *
 * The map is keyed by the BPMN id of the element carrying the multi-instance
 * characteristics, and its iteration order is outermost first: a task inside a
 * multi-instance subprocess which itself iterates lists the subprocess before the task. A
 * level whose <code>loopCounter</code> or <code>nrOfInstances</code> the engine does not
 * hold is left out rather than guessed, and an activity whose model declares no element
 * variable yields a <code>null</code> element.
 *
 * <h2>What it does not promise</h2>
 *
 * Nothing about an execution the engine no longer holds: a completed or cancelled task
 * answers an empty map, which is the same answer a task that never was in a multi-instance
 * activity gives. The two cannot be told apart here, and a caller which has to tell them
 * apart asks the engine's history instead.
 *
 * <p>
 * The promises above are held by <code>Camunda7MultiInstancesTest</code>.
 */
public final class Camunda7MultiInstances {

  /**
   * The attribute naming the variable each iteration gets its item in. It is read
   * namespace-generically rather than through the typed getter of the Camunda model API:
   * the Camunda 7 forks renamed those getters along with their packages, and an attribute
   * read by namespace and name survives that rename.
   */
  private static final String ELEMENT_VARIABLE_ATTRIBUTE = "elementVariable";

  private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

  private Camunda7MultiInstances() {
  }

  /**
   * The multi-instance scopes of an execution the engine handed over, which is what a
   * delegate, a listener or a task behaviour has at hand.
   *
   * @param execution The execution, or <code>null</code>
   * @return The scopes, keyed by BPMN element id and outermost first, never
   *         <code>null</code>
   */
  public static Map<String, MultiInstanceValue> of(
      final DelegateExecution execution) {

    if (execution == null) {
      return Map.of();
    }
    return collect(execution);

  }

  /**
   * The multi-instance scopes of an execution named by its id, for a caller which has no
   * execution at hand - a user task read from the engine's query API above all. The walk
   * needs the execution tree, which lives inside an engine command, so this runs one.
   *
   * @param engine The engine holding the execution
   * @param executionId The execution, or <code>null</code>
   * @return The scopes, keyed by BPMN element id and outermost first, never
   *         <code>null</code>
   */
  public static Map<String, MultiInstanceValue> of(
      final ProcessEngine engine,
      final String executionId) {

    if ((engine == null) || (executionId == null)) {
      return Map.of();
    }
    return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired()
        .execute(commandContext -> {
          final var execution = commandContext
              .getExecutionManager()
              .findExecutionById(executionId);
          return execution == null
              ? Map.<String, MultiInstanceValue>of()
              : collect(execution);
        });

  }

  private static Map<String, MultiInstanceValue> collect(
      final DelegateExecution execution) {

    final var element = execution.getBpmnModelElementInstance();
    if (element == null) {
      return Map.of();
    }
    final var model = element.getModelInstance();

    // the walk collects innermost first - the promise above is outermost first
    final var innermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    var current = execution;
    while (current != null) {
      multiInstanceOf(model, current)
          .ifPresent(scope -> innermostFirst.put(scope.elementId(), scope.value()));
      // upwards through the scopes of this process definition first, and where there is
      // no parent left through the call activity which started it
      current = current.getParentId() != null
          ? ((ExecutionEntity) current).getParent()
          : current.getSuperExecution();
    }

    final var outermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    outermostFirst.putAll(innermostFirst.reversed());
    return java.util.Collections.unmodifiableMap(outermostFirst);

  }

  /**
   * One collected level: the element carrying the multi-instance characteristics and what
   * the engine holds for the iteration at hand.
   */
  private record Scope(
                       String elementId,
                       MultiInstanceValue value) {
  }

  private static java.util.Optional<Scope> multiInstanceOf(
      final ModelInstance model,
      final DelegateExecution execution) {

    if (!(currentElementOf(model, execution) instanceof final Activity activity)) {
      return java.util.Optional.empty();
    }
    if (!(activity.getLoopCharacteristics() instanceof final MultiInstanceLoopCharacteristics loop)) {
      return java.util.Optional.empty();
    }
    final var index = execution.getVariable(MultiInstanceActivityBehavior.LOOP_COUNTER);
    final var total = execution.getVariable(MultiInstanceActivityBehavior.NUMBER_OF_INSTANCES);
    if (!(index instanceof final Integer itemNo) || !(total instanceof final Integer totalCount)) {
      return java.util.Optional.empty();
    }
    final var elementVariable = loop.getAttributeValueNs(CAMUNDA_NS, ELEMENT_VARIABLE_ATTRIBUTE);
    final var item = elementVariable == null
        ? null
        : execution.getVariable(elementVariable);
    return java.util.Optional
        .of(new Scope(activity.getId(), new MultiInstanceValue(item, itemNo.intValue(), totalCount.intValue())));

  }

  /**
   * The BPMN element an execution stands on. An execution of an embedded subprocess has
   * none of its own, and its activity instance id then names the element as
   * <code>&lt;element id&gt;:&lt;instance id&gt;</code>.
   */
  private static ModelElementInstance currentElementOf(
      final ModelInstance model,
      final DelegateExecution execution) {

    if (execution.getBpmnModelElementInstance() != null) {
      return execution.getBpmnModelElementInstance();
    }
    final var activityInstanceId = execution.getActivityInstanceId();
    if (activityInstanceId == null) {
      return null;
    }
    final var elementMarker = activityInstanceId.indexOf(':');
    return elementMarker == -1
        ? null
        : model.getModelElementById(activityInstanceId.substring(0, elementMarker));

  }

}
