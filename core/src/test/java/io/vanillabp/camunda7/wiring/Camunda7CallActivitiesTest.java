package io.vanillabp.camunda7.wiring;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaIn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 61: Camunda 7 does not pass its business key - which carries the workflow
 * aggregate's ID - to a called process. VanillaBP injects the propagation while
 * preparing the BPMN, but only where it is right.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CallActivitiesTest {

  private static final String MODULE = "loan-approval";

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
          id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="LoanApproval" isExecutable="true">
          <bpmn:callActivity id="assessRisk" calledElement="RiskAssessment"/>
          <bpmn:callActivity id="chargeCard" calledElement="Payment"/>
          <bpmn:callActivity id="whateverTheApplicationDecides" calledElement="${processToCall}"/>
          <bpmn:subProcess id="documents">
            <bpmn:callActivity id="collectDocuments" calledElement="DocumentCollection"/>
          </bpmn:subProcess>
          <bpmn:callActivity id="assessRiskWithOwnKey" calledElement="RiskAssessment">
            <bpmn:extensionElements>
              <camunda:in businessKey="#{execution.getVariable('otherKey')}"/>
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * The core's answer: everything of this module works on the aggregate of
   * 'LoanApproval' except 'Payment', which has one of its own.
   */
  private static final WorkflowTaskInvoker CORE = new WorkflowTaskInvoker() {

    @Override
    public boolean workflowsShareTheWorkflowAggregate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String otherBpmnProcessId) {

      return MODULE.equals(workflowModuleId) && "LoanApproval"
          .equals(bpmnProcessId) && !"Payment".equals(otherBpmnProcessId);

    }

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final java.util.Collection<BpmnTaskSpec> tasks) {
    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {
    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {

      throw new UnsupportedOperationException("not part of this test");

    }

    @Deprecated(forRemoval = true)
    @Override
    public boolean workflowAggregateHasProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String propertyName) {

      return false;

    }

    @Deprecated(forRemoval = true)
    @Override
    public Object resolveWorkflowAggregateProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String propertyName) {

      return null;

    }

    @Override
    public java.util.Map<String, Object> syncedWorkflowAggregateValuesInCurrentTransaction(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

      return java.util.Map.of();

    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {

      return false;

    }

    @Override
    public java.util.Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

      return java.util.Map.of();

    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return "id";

    }

  };

  private static BpmnModelInstance preparedModel() {

    final var model = Bpmn.readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(UTF_8)));
    Camunda7CallActivities.propagateBusinessKey(model, MODULE, CORE);
    return model;

  }

  private static List<String> businessKeysOf(
      final BpmnModelInstance model,
      final String callActivityId) {

    final var callActivity = (CallActivity) model.getModelElementById(callActivityId);
    final var extensionElements = callActivity.getExtensionElements();
    if (extensionElements == null) {
      return List.of();
    }
    return extensionElements
        .getElementsQuery()
        .filterByType(CamundaIn.class)
        .list()
        .stream()
        .map(CamundaIn::getCamundaBusinessKey)
        .filter(java.util.Objects::nonNull)
        .toList();

  }

  @Test
  @DisplayName("A process called on the same workflow aggregate is handed the business key")
  public void sameAggregateGetsTheBusinessKey() {

    final var model = preparedModel();

    assertEquals(
        List.of(Camunda7CallActivities.PARENT_BUSINESS_KEY_EXPRESSION),
        businessKeysOf(model, "assessRisk"));
    // also inside a subprocess - the call activity's process is found by walking up
    assertEquals(
        List.of(Camunda7CallActivities.PARENT_BUSINESS_KEY_EXPRESSION),
        businessKeysOf(model, "collectDocuments"));

  }

  @Test
  @DisplayName("A process with an aggregate of its own is not handed the caller's identity")
  public void otherAggregateKeepsItsOwnIdentity() {

    assertEquals(List.of(), businessKeysOf(preparedModel(), "chargeCard"));

  }

  @Test
  @DisplayName("A called element which is an expression addresses a process VanillaBP does not know")
  public void expressionsAreLeftAlone() {

    assertEquals(List.of(), businessKeysOf(preparedModel(), "whateverTheApplicationDecides"));

  }

  @Test
  @DisplayName("What the application modelled wins")
  public void modelledBusinessKeyIsKept() {

    final var businessKeys = businessKeysOf(preparedModel(), "assessRiskWithOwnKey");

    assertEquals(1, businessKeys.size(), () -> businessKeys.toString());
    assertTrue(
        businessKeys
            .getFirst()
            .contains("otherKey"),
        () -> businessKeys.toString());

  }

}
