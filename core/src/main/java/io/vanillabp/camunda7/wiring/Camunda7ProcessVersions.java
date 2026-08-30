package io.vanillabp.camunda7.wiring;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The versions of the process definitions of ONE Camunda 7 engine (= one adapter id):
 * what the core matches <code>&#64;WorkflowTask(version = ...)</code> and its siblings
 * against.
 * <p>
 * Two things are cached here, both for the same reason - the engine delivers tasks
 * inside its own transaction, and a query per task execution would be paid by every
 * workflow:
 * <ul>
 * <li>the version per process DEFINITION ID: the identifier an execution reports is
 * resolved once and then answered from memory (see
 * {@link #versionOfDefinition(String)});</li>
 * <li>the deployed versions per BPMN process, filled by the deployment (which reports
 * what it deployed) and by a definition query for everything deployed before or
 * elsewhere - the query only ever runs where a version TAG is involved.</li>
 * </ul>
 */
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda7ProcessVersions extends CachingProcessVersionCatalog {

  /**
   * The adapter ID - this catalog belongs to ONE engine, and what it reports names it.
   */
  private final String adapterId;

  private final RepositoryService repositoryService;

  /**
   * The process definition key the ENGINE knows for a (workflow module, plain BPMN
   * process id) - the identifiers may be prefixed.
   */
  private final BiFunction<String, String, String> scopedProcessIds;

  /**
   * The tenant a workflow module is deployed to, or <code>null</code>.
   */
  private final Function<String, String> tenants;

  /**
   * The version per process definition id - the engine's definition ids are stable,
   * so this map only grows by the number of deployed versions.
   */
  private final Map<String, String> versionsByDefinitionId = new ConcurrentHashMap<>();

  /**
   * The engine's process definition id per (workflow module, BPMN process, version).
   * <p>
   * The startup check for old versions asks two things about every version older than the
   * one this boot deployed, its model and how many workflows run on it, and both need this
   * id. Looking it up with a definition query per question meant three queries per version
   * where one list already held the answer: {@link #fetchDeployedVersions} reads the
   * definitions and used to keep nothing but their version numbers. So it keeps the ids
   * as well, and the query below runs only for a version which was deployed after that
   * list was read - see decision 10 in the repository's DECISIONS.md.
   */
  private final Map<String, String> definitionIdsByVersion = new ConcurrentHashMap<>();

  /**
   * The version this boot deployed per process - what tells a restart
   * without a model change that it still has to report a version.
   */
  private final Map<String, String> deployedVersions = new ConcurrentHashMap<>();

  /**
   * Reads the tasks of a model the engine holds - the deployment service' own
   * extraction.
   */
  @FunctionalInterface
  public interface TasksOfModel {

    java.util.Collection<BpmnTaskSpec> of(
        String workflowModuleId,
        String bpmnProcessId,
        String version,
        BpmnModelInstance model);

  }

  private final TasksOfModel tasksOfModel;

  /**
   * The engine's runtime, asked how many workflows still run on an old version.
   * Set once the engine is there; <code>null</code> switches the
   * question off.
   */
  private RuntimeService runtimeService;

  public Camunda7ProcessVersions(
      final String adapterId,
      final RepositoryService repositoryService,
      final BiFunction<String, String, String> scopedProcessIds,
      final Function<String, String> tenants,
      final TasksOfModel tasksOfModel) {

    this.adapterId = adapterId;
    this.repositoryService = repositoryService;
    this.scopedProcessIds = scopedProcessIds;
    this.tenants = tenants;
    this.tasksOfModel = tasksOfModel;

  }

  /**
   * @param runtimeService The engine's runtime service
   */
  public void setRuntimeService(
      final RuntimeService runtimeService) {

    this.runtimeService = runtimeService;

  }

  /**
   * The version this adapter recorded for that process during this boot, or
   * <code>null</code> if it deployed nothing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The version or <code>null</code>
   */
  public String deployedVersionOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return deployedVersions.get(workflowModuleId
        + "|"
        + bpmnProcessId);

  }

  @Override
  public java.util.Collection<BpmnTaskSpec> tasksOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (tasksOfModel == null) {
      return null;
    }
    final var definitionId = definitionIdOf(workflowModuleId, bpmnProcessId, version);
    if (definitionId == null) {
      // the engine does not hold that version any more (a deployment was deleted
      // between the query and this call) - nothing to check, and nothing to warn
      // about either
      return java.util.List.of();
    }
    return tasksOfModel
        .of(workflowModuleId, bpmnProcessId, version, repositoryService.getBpmnModelInstance(definitionId));

  }

  @Override
  public Long activeInstanceCountOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (runtimeService == null) {
      return null;
    }
    final var definitionId = definitionIdOf(workflowModuleId, bpmnProcessId, version);
    if (definitionId == null) {
      return 0L;
    }
    return runtimeService
        .createProcessInstanceQuery()
        .processDefinitionId(definitionId)
        .active()
        .count();

  }

  /**
   * The engine's process definition id of one version of a process - from what the
   * version list already brought back, and only otherwise from a query of its own.
   */
  private String definitionIdOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (!version.matches("\\d+")) {
      return null;
    }
    final var known = definitionIdsByVersion.get(versionKey(workflowModuleId, bpmnProcessId, version));
    if (known != null) {
      return known;
    }
    return askTheEngineForTheDefinitionId(workflowModuleId, bpmnProcessId, version);

  }

  private static String versionKey(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, version);

  }

  private String askTheEngineForTheDefinitionId(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var scopedProcessId = scopedProcessIds.apply(workflowModuleId, bpmnProcessId);
    final var tenantId = tenants.apply(workflowModuleId);
    var query = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(scopedProcessId)
        .processDefinitionVersion(Integer.valueOf(version));
    query = tenantId == null
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId);
    final var definition = query.singleResult();
    if (definition == null) {
      return null;
    }
    remember(workflowModuleId, bpmnProcessId, definition);
    return definition.getId();

  }

  /**
   * Keeps what a definition query brought back, so the next question about the same
   * version is answered without asking again.
   */
  private void remember(
      final String workflowModuleId,
      final String bpmnProcessId,
      final org.camunda.bpm.engine.repository.ProcessDefinition definition) {

    final var version = String.valueOf(definition.getVersion());
    definitionIdsByVersion.put(versionKey(workflowModuleId, bpmnProcessId, version), definition.getId());
    versionsByDefinitionId.put(definition.getId(), version);

  }

  /**
   * The version of a running execution's process definition, resolved ONCE per
   * definition id.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version as a string, or <code>null</code> if the engine does not know
   *         that definition (any more)
   */
  public String versionOfDefinition(
      final String processDefinitionId) {

    if (processDefinitionId == null) {
      return null;
    }
    // an unknown definition is remembered as well (empty string), so a definition
    // the engine dropped does not cause a query per task execution either
    final var version = versionsByDefinitionId
        .computeIfAbsent(
            processDefinitionId,
            definitionId -> {
              final var definition = repositoryService.getProcessDefinition(definitionId);
              return definition == null
                  ? ""
                  : String.valueOf(definition.getVersion());
            });
    return version.isEmpty()
        ? null
        : version;

  }

  /**
   * Remembers a version the deployment reported - the deploy result names the version
   * the engine assigned to the model just deployed, including its
   * <code>camunda:versionTag</code>.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param processDefinitionId The engine's process definition id
   * @param version The version the engine assigned
   * @param versionTag The version tag of the model or <code>null</code>
   */
  public void recordDeployed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId,
      final int version,
      final String versionTag) {

    versionsByDefinitionId.put(processDefinitionId, String.valueOf(version));
    definitionIdsByVersion
        .put(versionKey(workflowModuleId, bpmnProcessId, String.valueOf(version)), processDefinitionId);
    deployedVersions.put(workflowModuleId
        + "|"
        + bpmnProcessId, String.valueOf(version));
    record(workflowModuleId, bpmnProcessId, DeployedProcessVersion.of(String.valueOf(version), versionTag));

  }

  @Override
  protected List<DeployedProcessVersion> fetchDeployedVersions(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var scopedProcessId = scopedProcessIds.apply(workflowModuleId, bpmnProcessId);
    final var tenantId = tenants.apply(workflowModuleId);
    var query = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(scopedProcessId);
    query = tenantId == null
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId);
    final var definitions = query
        .orderByProcessDefinitionVersion()
        .asc()
        .list();
    // this one list holds what every later question about an older version needs, and
    // keeping it is what spares those questions a definition query each. A suspended
    // definition is remembered along with the rest even where SuspendedProcessDefinitions
    // takes it out of the answer: its workflows keep running and keep reporting the
    // version they are on
    definitions.forEach(definition -> remember(workflowModuleId, bpmnProcessId, definition));
    return SuspendedProcessDefinitions
        .definitionsWhichStillCount(adapterId, workflowModuleId, bpmnProcessId, definitions)
        .stream()
        .map(definition -> DeployedProcessVersion
            .of(String.valueOf(definition.getVersion()), definition.getVersionTag()))
        .toList();

  }

}
