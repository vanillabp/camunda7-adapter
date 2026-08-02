package io.vanillabp.camunda7.wiring;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The task connectables of ONE Camunda 7 engine (= one adapter id), registered by
 * the deployment service during <code>wireBpmn</code> and looked up by the
 * {@link Camunda7TaskELResolver} whenever the engine evaluates a top-level EL name.
 * Keyed by (tenant ID = workflow module ID, BPMN process ID) - one engine serves
 * several workflow modules.
 */
public class Camunda7TaskRegistry {

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private final Map<RegistryKey, List<Camunda7TaskConnectable>> connectables = new ConcurrentHashMap<>();

  public void register(
      final Camunda7TaskConnectable connectable) {

    connectables
        .computeIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.bpmnProcessId()),
            key -> new CopyOnWriteArrayList<>())
        .add(connectable);

  }

  /**
   * Resolves the connectable serving the given EL name - matching by the BPMN
   * element the expression is evaluated at, or by the task definition (the EL
   * name itself).
   *
   * @param workflowModuleId The workflow module (= tenant) ID
   * @param bpmnProcessId The BPMN process ID
   * @param currentElementId The BPMN element the expression evaluates at (may be
   *          <code>null</code>)
   * @param propertyName The top-level EL name
   * @return The connectable or empty (the EL name references an aggregate
   *         attribute instead)
   */
  public Optional<Camunda7TaskConnectable> resolve(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String currentElementId,
      final String propertyName) {

    return connectables
        .getOrDefault(new RegistryKey(workflowModuleId, bpmnProcessId), List.of())
        .stream()
        .filter(connectable -> connectable.applies(currentElementId, propertyName))
        .findFirst();

  }

}
