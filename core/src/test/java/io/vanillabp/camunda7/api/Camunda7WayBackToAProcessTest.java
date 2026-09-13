package io.vanillabp.camunda7.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.deployment.AnEngineHolding;
import io.vanillabp.camunda7.wiring.Camunda7ProcessVersions;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The way back from what an engine reports to what the application wrote, and the version
 * behind a process definition id - the two answers a reader of this engine would otherwise
 * build a registry and a query of its own for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WayBackToAProcessTest {

  private static final String ADAPTER_ID = "camunda7";

  private static final String MODULE = "loan-approval";

  @Test
  @DisplayName("A tenant and a process definition key become the module and the plain process id")
  public void aTenantAndAKeyBecomeTheModuleAndThePlainProcessId() {

    final var registry = new Camunda7TaskRegistry();
    registry.registerProcess(MODULE, "RiskAssessment", "RiskAssessment");

    final var resolved = registry.resolve(MODULE, "RiskAssessment");

    assertTrue(resolved.isPresent(), "the process was wired, so it is known");
    assertEquals(MODULE, resolved.get().workflowModuleId());
    assertEquals("RiskAssessment", resolved.get().bpmnProcessId());

  }

  @Test
  @DisplayName("Without a tenant the prefixed key finds the module and the plain id behind it")
  public void withoutATenantThePrefixedKeyFindsTheModuleAndThePlainId() {

    final var registry = new Camunda7TaskRegistry();
    registry.registerProcess(MODULE, "RiskAssessment", "loan-approval-RiskAssessment");

    final var resolved = registry.resolve(null, "loan-approval-RiskAssessment");

    assertTrue(resolved.isPresent());
    assertEquals(MODULE, resolved.get().workflowModuleId());
    assertEquals(
        "RiskAssessment",
        resolved.get().bpmnProcessId(),
        "the plain id is the one that was registered, never one parsed out of the key");

  }

  @Test
  @DisplayName("A process this application did not deploy resolves to nothing")
  public void aProcessThisApplicationDidNotDeployResolvesToNothing() {

    final var registry = new Camunda7TaskRegistry();
    registry.registerProcess(MODULE, "RiskAssessment", "RiskAssessment");

    assertTrue(registry.resolve(MODULE, "SomethingElse").isEmpty(), "same tenant, a process of another application");
    assertTrue(registry.resolve("another-tenant", "RiskAssessment").isEmpty(), "another tenant");
    assertTrue(registry.resolve(MODULE, null).isEmpty(), "no key");

  }

  @Test
  @DisplayName("The version behind a definition id is answered from the adapter's cache")
  public void theVersionBehindADefinitionIdIsAnsweredFromTheCache() {

    final var registry = new Camunda7TaskRegistry();
    final var processVersions = new Camunda7ProcessVersions(
        ADAPTER_ID, AnEngineHolding.theseModels("RiskAssessment", Map.of()), (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> workflowModuleId, null);
    registry.setProcessVersions(processVersions);
    processVersions.recordDeployed(MODULE, "RiskAssessment", "definition-4", 4, "release-7");

    final var deployed = registry.definitionOf("definition-4");

    assertEquals("4", deployed.version());
    assertEquals("release-7", deployed.versionTag());
    // the platform writes how an operator reads it, so every BPMS spells a deployment alike
    assertEquals("release-7:4", deployed.displayVersion());
    assertEquals("4", registry.versionOfDefinition("definition-4"), "the same cache answers the number");

  }

}
