package io.vanillabp.camunda7.wiring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.camunda.bpm.engine.RepositoryService;

/**
 * The source this adapter writes into every Camunda deployment, and how it is recognised
 * again. It is <code>&lt;adapter id&gt;:&lt;workflow module id&gt;</code>. The deployment is named
 * after the workflow module, so the source of such a deployment ends with a colon and the
 * deployment's name.
 * <p>
 * Version 1 named its deployments after the workflow module as well, but it wrote the
 * application's name into the source. So a deployment named after a workflow module whose
 * source does not end with that name holds models which version 1 deployed. Version 1
 * deployed the file as it was. This adapter did not prepare those models.
 * <p>
 * Decision 17 in the repository's DECISIONS.md says what the source is used for.
 */
public final class Camunda7DeploymentSource {

  private Camunda7DeploymentSource() {
    // utility class
  }

  /**
   * The source of a deployment this adapter makes.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module id, which is also the deployment's name
   * @return The source to write
   */
  public static String of(
      final String adapterId,
      final String workflowModuleId) {

    return adapterId
        + ":"
        + workflowModuleId;

  }

  /**
   * Whether a VanillaBP adapter of version 2 or later made a deployment. It may be this
   * adapter id or another one, and this workflow module or another one.
   *
   * @param deploymentName The deployment's name, possibly <code>null</code>
   * @param source The deployment's source, possibly <code>null</code>
   * @return Whether the source has the form this adapter writes
   */
  public static boolean writtenByAVanillaBpAdapter(
      final String deploymentName,
      final String source) {

    return (deploymentName != null) && (source != null) && source.endsWith(":"
        + deploymentName);

  }

  /**
   * Remembers per deployment whether this adapter prepared its models. A deployment never
   * changes, so the answer never gets out of date.
   */
  static final class PreparedDeployments {

    private final Map<String, Boolean> preparedByDeploymentId = new ConcurrentHashMap<>();

    /**
     * Whether a VanillaBP adapter prepared the models of a deployment. A deployment the
     * engine no longer finds counts as prepared, so nothing is guessed about it.
     *
     * @param repositoryService Where the deployment is looked up
     * @param deploymentId The deployment id
     * @return Whether its models were prepared before they were deployed
     */
    boolean prepared(
        final RepositoryService repositoryService,
        final String deploymentId) {

      return preparedByDeploymentId.computeIfAbsent(deploymentId, id -> {
        final var deployment = repositoryService
            .createDeploymentQuery()
            .deploymentId(id)
            .singleResult();
        return (deployment == null) || writtenByAVanillaBpAdapter(deployment.getName(), deployment.getSource());
      });

    }

  }

}
