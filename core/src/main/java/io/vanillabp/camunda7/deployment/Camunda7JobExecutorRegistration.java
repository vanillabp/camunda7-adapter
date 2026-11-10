package io.vanillabp.camunda7.deployment;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the job executor of an engine which deployments it serves.
 * <p>
 * The engine works deployment-aware, as in version 1: its job executor fetches only the jobs
 * of deployments registered with it. The deploy command registers just the one deployment it
 * creates, or the duplicate it finds. A workflow which started on an older version runs on
 * an older deployment, though, with its timers and asynchronous continuations. Without a
 * registration nobody fetches these jobs after a restart. Such a workflow stops without an
 * error and without an incident.
 * <p>
 * So the adapter registers every deployment named after the workflow module, in the tenant
 * the module deploys into. Every generation of VanillaBP deployed a workflow module under the
 * module's id, version 1 included, so this finds what version 1 left behind as well. The
 * source of a deployment is not read: version 1 wrote the application name there, and the
 * adapter's own format changes too. Each of these deployments held the full set of models of
 * the module at that time.
 * <p>
 * A deployment of another workflow module or of another application carries another name and
 * stays unregistered. A node with the old code of this application does not know a deployment
 * which a newer node made after it started. So during a rolling update it leaves the jobs of
 * the newest version to the nodes which have the code for them. That is what the setting is
 * for. See decision 59 in the repository's DECISIONS.md.
 */
final class Camunda7JobExecutorRegistration {

  private static final Logger log = LoggerFactory.getLogger(Camunda7JobExecutorRegistration.class);

  private Camunda7JobExecutorRegistration() {
    // static helper
  }

  /**
   * Registers every deployment of a workflow module with the engine's job executor. It costs
   * one query per workflow module and boot. The rows grow with the number of deployments
   * which changed a model, like the versions the startup check reads.
   *
   * @param adapterId The adapter id whose engine this is
   * @param workflowModuleId The workflow module, which is also the name of its deployments
   * @param tenantId The tenant the module deploys into, <code>null</code> for none
   * @param repositoryService Where the deployments are found
   * @param managementService Where they are registered
   * @return The number of deployments registered
   */
  static int registerEveryDeploymentOf(
      final String adapterId,
      final String workflowModuleId,
      final String tenantId,
      final RepositoryService repositoryService,
      final ManagementService managementService) {

    var query = repositoryService
        .createDeploymentQuery()
        .deploymentName(workflowModuleId);
    query = (tenantId == null)
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId);
    final var deploymentIds = query
        .list()
        .stream()
        .map(Deployment::getId)
        .toList();
    deploymentIds.forEach(managementService::registerDeploymentForJobExecutor);

    log
        .info(
            "Camunda7[{}]: the job executor fetches the jobs of {} deployment(s) of workflow module '{}' "
                + "(tenant '{}'), the current one and every earlier version. Jobs of any other deployment "
                + "are left to the nodes which made it.",
            adapterId,
            deploymentIds.size(),
            workflowModuleId,
            (tenantId == null)
                ? "<none>"
                : tenantId);
    return deploymentIds.size();

  }

}
