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
   * The versions of the engine's process definitions (story 48) - handed over by the
   * deployment service, which owns the engine's {@code RepositoryService}. Every
   * listener building an invocation context reaches it through this registry. May be
   * <code>null</code> (tests): no version is reported then, which matches every
   * method.
   */
  private Camunda7ProcessVersions processVersions;

  /**
   * The id of the adapter owning this engine. Reported with every inbound delivery,
   * so VanillaBP can record which BPMS holds a workflow (story 54). May be
   * <code>null</code> (tests): nothing is recorded then.
   */
  private String adapterId;

  /**
   * @param adapterId The id of the adapter owning this engine
   */
  public void setAdapterId(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  /**
   * @return The id of the adapter owning this engine or <code>null</code>
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * @param processVersions The versions of the engine's process definitions
   */
  public void setProcessVersions(
      final Camunda7ProcessVersions processVersions) {

    this.processVersions = processVersions;

  }

  /**
   * The version of a running execution's process definition, matched against the
   * <code>version</code> attribute of the application's annotations.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version or <code>null</code> if it cannot be determined
   */
  public String versionOfDefinition(
      final String processDefinitionId) {

    return processVersions == null
        ? null
        : processVersions.versionOfDefinition(processDefinitionId);

  }

  /**
   * Which workflow module a process definition key belongs to - the way back when
   * there is no tenant to ask (prefixed identifiers, story 35).
   */
  private final Map<String, String> workflowModuleIdsByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * The plain BPMN process id per (workflow module, scoped process id) - filled for
   * every wired process, so a process without tasks is found as well.
   */
  private final Map<RegistryKey, String> plainProcessIdsByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * The PLAIN signal name per signal start event, keyed by (workflow module, scoped
   * process id, start event id): the engine's parser cannot resolve a signalRef, and
   * the name the application is told has to be the modelled one (story 35).
   */
  private final Map<String, String> signalNamesOfStartEvents = new ConcurrentHashMap<>();

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
    plainProcessIdsByScopedProcessId
        .putIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.scopedBpmnProcessId()),
            connectable.bpmnProcessId());

  }

  /**
   * Registers what a process is called on both sides, without any task being
   * involved: a process the BPMS starts on its own (timer, signal or conditional
   * start event) may have no tasks at all, and the start listener still has to find
   * its way back from the engine's process-definition key to the workflow module and
   * the plain BPMN process id.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   */
  public void registerProcess(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId) {

    plainProcessIdsByScopedProcessId
        .putIfAbsent(new RegistryKey(workflowModuleId, scopedBpmnProcessId), bpmnProcessId);
    workflowModuleIdsByScopedProcessId.putIfAbsent(scopedBpmnProcessId, workflowModuleId);

  }

  /**
   * Registers the plain signal name of a signal start event.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param startEventId The BPMN id of the start event
   * @param signalName The PLAIN signal name
   */
  public void registerSignalStartEvent(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String startEventId,
      final String signalName) {

    if (signalName == null) {
      return;
    }
    signalNamesOfStartEvents
        .putIfAbsent(signalKey(workflowModuleId, scopedBpmnProcessId, startEventId), signalName);

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine reported
   * @param startEventId The BPMN id of the start event which fired
   * @return The plain signal name or <code>null</code>
   */
  public String signalNameOfStartEvent(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String startEventId) {

    return signalNamesOfStartEvents.get(signalKey(workflowModuleId, scopedBpmnProcessId, startEventId));

  }

  private static String signalKey(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String startEventId) {

    return "%s|%s|%s".formatted(workflowModuleId, scopedBpmnProcessId, startEventId);

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

    final var registered = plainProcessIdsByScopedProcessId
        .get(new RegistryKey(workflowModuleId, processDefinitionKey));
    if (registered != null) {
      return registered;
    }
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

    // by NAME first: a name which IS a task definition means that task, wherever it
    // is evaluated. Only then by element, which matches any name evaluated there
    final var byName = connectables
        .getOrDefault(new RegistryKey(workflowModuleId, bpmnProcessId), List.of())
        .stream()
        .filter(connectable -> connectable.appliesByName(propertyName))
        .findFirst();
    if (byName.isPresent()) {
      return byName;
    }
    return connectables
        .getOrDefault(new RegistryKey(workflowModuleId, bpmnProcessId), List.of())
        .stream()
        .filter(connectable -> connectable.appliesByElement(currentElementId))
        .findFirst();

  }

  /**
   * Whether a connectable serves this EL name by NAME (its task definition), as
   * opposed to serving whatever is evaluated at its BPMN element.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The SCOPED BPMN process ID
   * @param propertyName The EL name
   * @return Whether a connectable is named like this
   */
  public boolean isTaskDefinitionName(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String propertyName) {

    return connectables
        .getOrDefault(new RegistryKey(workflowModuleId, bpmnProcessId), List.of())
        .stream()
        .anyMatch(connectable -> connectable.appliesByName(propertyName));

  }

}
