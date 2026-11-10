package io.vanillabp.camunda7.wiring;

/**
 * The source this adapter writes into every Camunda deployment, and how it is recognised
 * again. It is <code>&lt;adapter id&gt;:&lt;workflow module id&gt;</code>. The deployment is named
 * after the workflow module, so the source of such a deployment ends with a colon and the
 * deployment's name.
 * <p>
 * Version 1 named its deployments after the workflow module as well, but it wrote the
 * application's name into the source.
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

}
