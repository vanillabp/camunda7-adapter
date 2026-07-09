package io.vanillabp.camunda7;

import lombok.Getter;

/**
 * The adapter-specific processing context (the {@code PC} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}). One instance
 * is accumulated across all BPMN files of a workflow module during the deployment
 * pipeline.
 * <p>
 * For now it only carries the workflow module ID. Later stories will let it collect the
 * resources to be deployed to the embedded Camunda 7 engine (using the workflow module
 * ID as the Camunda tenant ID).
 */
@Getter
public class Camunda7ProcessingContext {

  private final String workflowModuleId;

  public Camunda7ProcessingContext(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

}
