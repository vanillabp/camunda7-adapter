package io.vanillabp.camunda7.wiring;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.camunda.bpm.engine.RepositoryService;

import io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;

/**
 * The versions of the process definitions of ONE Camunda 7 engine (= one adapter id):
 * what the core matches <code>&#64;WorkflowTask(version = ...)</code> and its siblings
 * against (story 48).
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
public class Camunda7ProcessVersions extends CachingProcessVersionCatalog {

  private final RepositoryService repositoryService;

  /**
   * The process definition key the ENGINE knows for a (workflow module, plain BPMN
   * process id) - the identifiers may be prefixed (story 35).
   */
  private final BiFunction<String, String, String> scopedProcessIds;

  /**
   * The tenant a workflow module is deployed to, or <code>null</code> (story 35).
   */
  private final Function<String, String> tenants;

  /**
   * The version per process definition id - the engine's definition ids are stable,
   * so this map only grows by the number of deployed versions.
   */
  private final Map<String, String> versionsByDefinitionId = new ConcurrentHashMap<>();

  public Camunda7ProcessVersions(
      final RepositoryService repositoryService,
      final BiFunction<String, String, String> scopedProcessIds,
      final Function<String, String> tenants) {

    this.repositoryService = repositoryService;
    this.scopedProcessIds = scopedProcessIds;
    this.tenants = tenants;

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
    return query
        .orderByProcessDefinitionVersion()
        .asc()
        .list()
        .stream()
        .map(definition -> DeployedProcessVersion
            .of(String.valueOf(definition.getVersion()), definition.getVersionTag()))
        .toList();

  }

}
