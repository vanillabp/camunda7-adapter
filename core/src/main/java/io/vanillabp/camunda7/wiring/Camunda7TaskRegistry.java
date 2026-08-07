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

  /**
   * Which workflow module a process definition key belongs to - the way back when
   * there is no tenant to ask (prefixed identifiers, story 35).
   */
  private final Map<String, String> workflowModuleIdsByScopedProcessId = new ConcurrentHashMap<>();

  public void register(
      final Camunda7TaskConnectable connectable) {

    // keyed by what the ENGINE reports at runtime: the scoped process id (equal to
    // the plain one unless the module's identifiers are prefixed, story 35)
    connectables
        .computeIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.scopedBpmnProcessId()),
            key -> new CopyOnWriteArrayList<>())
        .add(connectable);
    workflowModuleIdsByScopedProcessId
        .putIfAbsent(connectable.scopedBpmnProcessId(), connectable.workflowModuleId());

  }

  /**
   * The workflow module of a running execution. Camunda's tenant answers it whenever
   * the module is isolated by a tenant; with prefixed identifiers (story 35) there
   * is no tenant, so the module is looked up by the process definition key the
   * wiring registered - a KNOWN value, never parsed out of the key.
   *
   * @param tenantId The execution's tenant ID (may be <code>null</code>)
   * @param processDefinitionKey The execution's process definition key
   * @return The workflow module ID or <code>null</code> if unknown
   */
  public String resolveWorkflowModuleId(
      final String tenantId,
      final String processDefinitionKey) {

    if (tenantId != null) {
      return tenantId;
    }
    return workflowModuleIdsByScopedProcessId.get(processDefinitionKey);

  }

  /**
   * The PLAIN BPMN process id of a process definition key the engine reported.
   *
   * @param workflowModuleId The workflow module ID
   * @param processDefinitionKey The execution's process definition key
   * @return The plain BPMN process ID (the key itself if nothing was registered)
   */
  public String plainBpmnProcessId(
      final String workflowModuleId,
      final String processDefinitionKey) {

    return connectables
        .getOrDefault(new RegistryKey(workflowModuleId, processDefinitionKey), List.of())
        .stream()
        .map(Camunda7TaskConnectable::bpmnProcessId)
        .findFirst()
        .orElse(processDefinitionKey);

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
