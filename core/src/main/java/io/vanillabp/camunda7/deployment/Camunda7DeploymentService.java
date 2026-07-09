package io.vanillabp.camunda7.deployment;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import lombok.RequiredArgsConstructor;

/**
 * Camunda 7 implementation of the VanillaBP adapter deployment SPI. One instance exists
 * per configured adapter id (not per adapter type).
 * <p>
 * This is the Version-2 skeleton: only the identity/type/model-type methods are
 * implemented. The deployment pipeline methods deliberately throw
 * {@link UnsupportedOperationException} - they are implemented in later stories. They
 * must never silently do nothing, otherwise wiring bugs of later stories would be
 * hidden.
 */
@RequiredArgsConstructor
public class Camunda7DeploymentService implements AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext> {

  /**
   * The adapter type of the Camunda 7 adapter. There may be several adapter ids of this
   * type configured (e.g. two Camunda 7 engines side by side during a migration).
   */
  public static final String ADAPTER_TYPE = "camunda7";

  private final String adapterId;

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

    throw new UnsupportedOperationException("readBpmn is implemented in a later story");

  }

  @Override
  public Camunda7ProcessingContext prepareBpmn(
      final String workflowModuleId,
      final Camunda7ProcessingContext existingContext,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    throw new UnsupportedOperationException("prepareBpmn is implemented in a later story");

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda7ProcessingContext context) {

    throw new UnsupportedOperationException("wireBpmn is implemented in a later story");

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) throws IllegalStateException {

    throw new UnsupportedOperationException("deployResources is implemented in a later story");

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    throw new UnsupportedOperationException("startWorkflowProcessing is implemented in a later story");

  }

}
