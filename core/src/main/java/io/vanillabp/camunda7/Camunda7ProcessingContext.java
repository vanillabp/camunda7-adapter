package io.vanillabp.camunda7;

import java.util.LinkedHashMap;
import java.util.Map;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import lombok.Getter;

/**
 * The adapter-specific processing context (the {@code PC} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}). One instance
 * is accumulated across all BPMN files of a workflow module during the deployment
 * pipeline and finally handed to
 * {@link io.vanillabp.camunda7.deployment.Camunda7DeploymentService#deployResources(String, Camunda7ProcessingContext)}.
 * <p>
 * It collects the parsed BPMN models to be deployed as a single Camunda 7 deployment
 * (using the workflow module ID as the Camunda tenant ID). Models are keyed by their
 * BPMN file name so that a file containing several executable processes contributes its
 * model only once (the deployment pipeline calls {@code prepareBpmn} once per executable
 * process, all sharing the same file-level model instance).
 */
@Getter
public class Camunda7ProcessingContext {

  private final String workflowModuleId;

  /**
   * The BPMN models to be deployed, keyed by their file name. A {@link LinkedHashMap}
   * keeps the deployment order deterministic (helpful for reproducible deployments and
   * logging).
   */
  private final Map<String, BpmnModelInstance> resourcesByFilename = new LinkedHashMap<>();

  public Camunda7ProcessingContext(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * Remembers the given BPMN model for deployment. Adding the same file name again (once
   * per executable process of a multi-process file) keeps a single model instance.
   *
   * @param filename The BPMN file name (used as the deployment resource name)
   * @param model The parsed BPMN model
   */
  /**
   * The PLAIN BPMN process ids of the module's executable processes, collected in
   * {@code prepareBpmn} - the input of story 35's collision check (two processes must
   * not end up under the same prefixed identifier).
   */
  @lombok.Getter
  private final java.util.List<String> deployedProcessIds = new java.util.LinkedList<>();

  /**
   * Records an executable BPMN process of this workflow module.
   *
   * @param bpmnProcessId The plain BPMN process ID
   */
  public void recordDeployedProcess(
      final String bpmnProcessId) {

    if ((bpmnProcessId != null) && !deployedProcessIds.contains(bpmnProcessId)) {
      deployedProcessIds.add(bpmnProcessId);
    }

  }

  public void addResource(
      final String filename,
      final BpmnModelInstance model) {

    resourcesByFilename.putIfAbsent(filename, model);

  }

  /**
   * @return Whether no BPMN models were accumulated (e.g. a workflow module without any
   *         executable BPMN process).
   */
  public boolean isEmpty() {

    return resourcesByFilename.isEmpty();

  }

}
