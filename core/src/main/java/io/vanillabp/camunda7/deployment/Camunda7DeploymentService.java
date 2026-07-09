package io.vanillabp.camunda7.deployment;

import java.io.InputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 7 implementation of the VanillaBP adapter deployment SPI. One instance exists
 * per configured adapter id (not per adapter type).
 * <p>
 * The BPMN files of a workflow module are read into Camunda's own
 * {@link BpmnModelInstance} model, accumulated in a {@link Camunda7ProcessingContext} and
 * finally deployed as a single Camunda deployment. The <b>workflow module ID is used as
 * the Camunda tenant ID</b> (Version-1 behavior) so BPMN process ids are isolated between
 * modules; duplicate filtering is enabled so unchanged models are not redeployed on every
 * boot.
 * <p>
 * Task wiring ({@link #wireBpmn}) is intentionally a no-op (log only) in this story - the
 * embedded engine executes only what the BPMN itself defines; wiring {@code @WorkflowTask}
 * methods is a later story.
 */
@Slf4j
@RequiredArgsConstructor
public class Camunda7DeploymentService implements AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext> {

  /**
   * The adapter type of the Camunda 7 adapter. There may be several adapter ids of this
   * type configured (e.g. two Camunda 7 engines side by side during a migration).
   */
  public static final String ADAPTER_TYPE = "camunda7";

  private final String adapterId;

  /**
   * The embedded engine's repository service used to deploy BPMN resources. Provided by
   * the platform module (Spring Boot) which wires the embedded engine sharing the
   * application's data source and transaction manager.
   */
  private final RepositoryService repositoryService;

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

  @Override
  public Class<BpmnModelInstance> getModelType() {

    return BpmnModelInstance.class;

  }

  @Override
  public Class<Camunda7ProcessingContext> getProcessContextType() {

    return Camunda7ProcessingContext.class;

  }

  @Override
  public List<Map.Entry<String, BpmnModelInstance>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    final BpmnModelInstance model;
    try {
      model = Bpmn.readModelFromStream(bpmn);
    } catch (final RuntimeException e) {
      throw new BpmnParseException(
          "Failed to parse BPMN file '%s' of workflow module '%s'!".formatted(filename, workflowModuleId), e);
    }

    // one entry per executable process; all entries share the file-level model instance
    // (the whole file is deployed once, see Camunda7ProcessingContext#addResource)
    return model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(Process::isExecutable)
        .map(process -> (Map.Entry<String, BpmnModelInstance>) new SimpleImmutableEntry<>(process.getId(), model))
        .toList();

  }

  @Override
  public Camunda7ProcessingContext prepareBpmn(
      final String workflowModuleId,
      final Camunda7ProcessingContext existingContext,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    // the core passes null for the first BPMN of a workflow module
    final var context = existingContext != null
        ? existingContext
        : new Camunda7ProcessingContext(workflowModuleId);
    context.addResource(filename, model);
    return context;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda7ProcessingContext context) {

    // task wiring is a later story - nothing to wire yet
    log.debug(
        "Camunda7[{}]: not wiring BPMN process '{}' of file '{}' (workflow module '{}') - task wiring is a later story",
        adapterId,
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) throws IllegalStateException {

    // the core invokes deployResources for every (workflow module x prioritized adapter),
    // even for modules without any executable BPMN process
    if (bpmsProcessingContext == null || bpmsProcessingContext.isEmpty()) {
      log.debug(
          "Camunda7[{}]: no BPMN resources to deploy for workflow module '{}'",
          adapterId,
          workflowModuleId);
      return;
    }

    if (repositoryService == null) {
      throw new IllegalStateException(
          ("Camunda7[%s]: cannot deploy resources of workflow module '%s' - no embedded engine "
              + "is available! A Camunda 7 adapter requires a data source and a transaction manager "
              + "so the embedded engine can be wired.").formatted(adapterId, workflowModuleId));
    }

    // one deployment per workflow module; tenant id = workflow module id isolates BPMN
    // process ids between modules; duplicate filtering avoids redeploying unchanged models
    final var deploymentBuilder = repositoryService
        .createDeployment()
        .name(workflowModuleId)
        .source(ADAPTER_TYPE
            + ":"
            + adapterId)
        .tenantId(workflowModuleId)
        .enableDuplicateFiltering(true);

    bpmsProcessingContext
        .getResourcesByFilename()
        .forEach(deploymentBuilder::addModelInstance);

    final var deployment = deploymentBuilder.deploy();

    log.info(
        "Camunda7[{}]: deployed {} BPMN resource(s) of workflow module '{}' (tenant '{}') as deployment '{}'",
        adapterId,
        bpmsProcessingContext.getResourcesByFilename().size(),
        workflowModuleId,
        workflowModuleId,
        deployment.getId());

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // the job executor is engine-global and already running - nothing to do per module
    log.debug(
        "Camunda7[{}]: workflow processing of module '{}' is handled by the engine-global job executor",
        adapterId,
        workflowModuleId);

  }

}
