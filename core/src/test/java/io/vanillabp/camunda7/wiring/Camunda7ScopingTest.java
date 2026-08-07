package io.vanillabp.camunda7.wiring;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.Error;
import org.camunda.bpm.model.bpmn.instance.Escalation;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.Signal;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * Unit tests of {@link Camunda7Scoping} (story 35): which identifiers of a Camunda 7
 * model are rewritten in mode {@link NameClashAvoidance#USE_PREFIX} - and that task
 * definitions deliberately are not, because they are process-local in Camunda 7.
 */
public class Camunda7ScopingTest {

  private static final String ADAPTER_ID = "camunda7";

  private static final String MODULE = "loan-approval";

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
          id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="msg1" name="LoanApproved"/>
        <bpmn:signal id="sig1" name="Halt"/>
        <bpmn:escalation id="esc1" escalationCode="TooLate"/>
        <bpmn:error id="err1" name="Denied" errorCode="Denied"/>
        <bpmn:process id="RiskAssessment" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:serviceTask id="score" camunda:expression="${riskAssessment.scoreApplicant(execution)}"/>
          <bpmn:userTask id="approve" camunda:formKey="approveLoan"/>
          <bpmn:callActivity id="collectDocuments" calledElement="DocumentCollection"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
        <bpmn:process id="DocumentCollection" isExecutable="true">
          <bpmn:startEvent id="start2"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static BpmnModelInstance model() {

    return Bpmn.readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(UTF_8)));

  }

  private static List<String> processIds(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(Process.class)
        .stream()
        .map(Process::getId)
        .sorted()
        .toList();

  }

  private static <T extends org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance> T first(
      final BpmnModelInstance model,
      final Class<T> type) {

    return model
        .getModelElementsByType(type)
        .iterator()
        .next();

  }

  @Test
  @DisplayName("USE_PREFIX rewrites everything the engine resolves across definitions")
  public void usePrefixRewritesEngineWideIdentifiers() {

    final var model = model();

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new ScopingDouble(NameClashAvoidance.USE_PREFIX));

    assertEquals(
        List.of("loan-approval__DocumentCollection", "loan-approval__RiskAssessment"),
        processIds(model));
    assertEquals(
        "loan-approval__DocumentCollection",
        first(model, CallActivity.class).getCalledElement());
    assertEquals(
        "loan-approval__LoanApproved",
        first(model, Message.class).getName());
    assertEquals(
        "loan-approval__Halt",
        first(model, Signal.class).getName());
    assertEquals(
        "loan-approval__TooLate",
        first(model, Escalation.class).getEscalationCode());
    assertEquals(
        "loan-approval__Denied",
        first(model, Error.class).getErrorCode());

  }

  @Test
  @DisplayName("USE_PREFIX leaves task definitions untouched: they are process-local in Camunda 7")
  public void usePrefixKeepsTaskDefinitions() {

    final var model = model();

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new ScopingDouble(NameClashAvoidance.USE_PREFIX));

    assertEquals(
        "${riskAssessment.scoreApplicant(execution)}",
        first(model, ServiceTask.class).getCamundaExpression());
    assertEquals(
        "approveLoan",
        first(model, UserTask.class).getCamundaFormKey());

  }

  @Test
  @DisplayName("BY_ADAPTER and NONE leave the model unchanged - the tenant respectively nothing isolates")
  public void otherModesDoNotTouchTheModel() {

    for (final var mode : List.of(NameClashAvoidance.BY_ADAPTER, NameClashAvoidance.NONE)) {

      final var model = model();

      Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new ScopingDouble(mode));

      assertEquals(List.of("DocumentCollection", "RiskAssessment"), processIds(model), "mode "
          + mode);
      assertEquals(
          "LoanApproved",
          first(model, Message.class).getName(),
          "mode "
              + mode);
      assertFalse(Camunda7Scoping.prefixes(MODULE, ADAPTER_ID, new ScopingDouble(mode)));

    }

  }

  @Test
  @DisplayName("a missing scoping support means no prefixing at all")
  public void noScopingSupportMeansNoPrefixing() {

    final var model = model();

    assertFalse(Camunda7Scoping.prefixes(MODULE, ADAPTER_ID, null));
    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, null);

    assertEquals(List.of("DocumentCollection", "RiskAssessment"), processIds(model));

  }

  @Test
  @DisplayName("a call activity addressed by an expression keeps the expression, prefixed as literal text")
  public void callActivityByExpressionStaysEvaluable() {

    final var model = model();
    first(model, CallActivity.class).setCalledElement("${nextProcess}");

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new ScopingDouble(NameClashAvoidance.USE_PREFIX));

    // JUEL evaluates composite expressions (literal text + '${...}'), so the engine
    // resolves the prefixed process id of whatever the expression yields
    assertEquals(
        "loan-approval__${nextProcess}",
        first(model, CallActivity.class).getCalledElement());
    assertTrue(Camunda7Scoping.prefixes(MODULE, ADAPTER_ID, new ScopingDouble(NameClashAvoidance.USE_PREFIX)));

  }

  /**
   * A hand-written {@link NameClashAvoidanceSupport}: the Camunda 7 core depends on the
   * adapter SPI only, so the core's implementation is not available here. It composes
   * exactly like {@code NameClashAvoidanceService} does.
   */
  private static class ScopingDouble implements NameClashAvoidanceSupport {

    private final NameClashAvoidance mode;

    ScopingDouble(
        final NameClashAvoidance mode) {

      this.mode = mode;

    }

    @Override
    public NameClashAvoidance modeFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId) {

      return mode;

    }

    private String scoped(
        final String identifier,
        final String... prefixes) {

      if ((mode != NameClashAvoidance.USE_PREFIX) || (identifier == null)) {
        return identifier;
      }
      return String.join(SEPARATOR, prefixes) + SEPARATOR + identifier;

    }

    @Override
    public String scopedProcessId(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId) {

      return scoped(bpmnProcessId, workflowModuleId);

    }

    @Override
    public String scopedIdentifier(
        final String workflowModuleId,
        final String identifier,
        final String adapterId) {

      return scoped(identifier, workflowModuleId);

    }

    @Override
    public String scopedTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition,
        final String adapterId) {

      return scoped(taskDefinition, workflowModuleId, bpmnProcessId);

    }

    @Override
    public String plainProcessId(
        final String workflowModuleId,
        final String scopedBpmnProcessId,
        final String adapterId) {

      return strip(scopedBpmnProcessId, workflowModuleId);

    }

    @Override
    public String plainIdentifier(
        final String workflowModuleId,
        final String scopedIdentifier,
        final String adapterId) {

      return strip(scopedIdentifier, workflowModuleId);

    }

    @Override
    public String plainTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String scopedTaskDefinition,
        final String adapterId) {

      return strip(scopedTaskDefinition, workflowModuleId, bpmnProcessId);

    }

    private String strip(
        final String identifier,
        final String... prefixes) {

      if ((mode != NameClashAvoidance.USE_PREFIX) || (identifier == null)) {
        return identifier;
      }
      final var prefix = String.join(SEPARATOR, prefixes) + SEPARATOR;
      return identifier.startsWith(prefix) ? identifier.substring(prefix.length()) : identifier;

    }

    @Override
    public String tenantIdFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId,
        final String configuredTenantId) {

      if (mode != NameClashAvoidance.BY_ADAPTER) {
        return null;
      }
      return configuredTenantId != null ? configuredTenantId : workflowModuleId;

    }

    @Override
    public void validateNativeIsolationSupported(
        final String adapterId,
        final String workflowModuleId,
        final String bpmsDescription) {

    }

    @Override
    public void validateNoCollidingProcessIds(
        final String adapterId,
        final Collection<DeployedProcess> deployedProcesses) {

    }

  }

}
