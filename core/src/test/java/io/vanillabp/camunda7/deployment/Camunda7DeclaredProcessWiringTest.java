package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter wires for a BPMN process id the application DECLARES without deploying
 * a model under it - the old id of a renamed process, whose workflows keep running in the
 * engine.
 * <p>
 * The models come out of the engine's repository, and this is where the two things that
 * costs are measured. The way a task is wired travels with the model, so a
 * <code>camunda:delegateExpression</code> stays one and does not become an expression; and
 * a model the extraction refuses does not end the boot, because nobody can change a model
 * which was deployed years ago. {@code Camunda7RenamedProcessIT} asks the same of a running
 * engine.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DeclaredProcessWiringTest {

  private static final String MODULE = "loan-approval";

  private static final String OLD_ID = "loan_approval";

  /**
   * A model whose two tasks are wired the two ways VanillaBP knows here.
   */
  private static final String A_MODEL_WITH_BOTH_KINDS_OF_TASK = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}" />
          <bpmn:serviceTask id="Activity_approve" camunda:delegateExpression="${approve}" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A model wired by an external task, which this adapter refuses - what a model deployed
   * by another tool, or by a VanillaBP version which accepted more, can look like.
   */
  private static final String A_MODEL_THIS_ADAPTER_REFUSES = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Older" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:serviceTask id="Activity_external" camunda:topic="someTopic" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("Every version the engine holds under the declared id is wired, with the task kinds of its model")
  public void theVersionsHeldUnderTheDeclaredIdAreWired() {

    final var taskRegistry = new Camunda7TaskRegistry();
    final var service = adapterServing(
        taskRegistry,
        Map.of("1", A_MODEL_WITH_BOTH_KINDS_OF_TASK));

    service.startWorkflowProcessing(MODULE, new Camunda7ProcessingContext(MODULE));

    assertEquals(
        Camunda7TaskConnectable.Type.EXPRESSION,
        taskRegistry
            .resolve(MODULE, OLD_ID, "Activity_check", "checkCredit")
            .orElseThrow(() -> new AssertionError("the expression task of the old model was not wired"))
            .type(),
        "a camunda:expression task completes when its handler returns, and the model is what says so");
    assertEquals(
        Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION,
        taskRegistry
            .resolve(MODULE, OLD_ID, "Activity_approve", "approve")
            .orElseThrow(() -> new AssertionError("the delegate-expression task of the old model was not wired"))
            .type(),
        "a camunda:delegateExpression task can stay open, which no list of task definitions could tell");
    assertEquals(
        OLD_ID,
        taskRegistry.plainBpmnProcessId(MODULE, OLD_ID),
        "the way back from the engine's definition key is registered as well");

  }

  @Test
  @DisplayName("A version the extraction refuses is one warning, and the others are wired anyway")
  public void aVersionWhichCannotBeWiredDoesNotEndTheBoot(
      final CapturedOutput output) {

    final var taskRegistry = new Camunda7TaskRegistry();
    final var service = adapterServing(
        taskRegistry,
        // an old generation the adapter cannot read, and the one it can
        new java.util.LinkedHashMap<>(
            Map.of("1", A_MODEL_THIS_ADAPTER_REFUSES, "2", A_MODEL_WITH_BOTH_KINDS_OF_TASK)));

    service.startWorkflowProcessing(MODULE, new Camunda7ProcessingContext(MODULE));

    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("version 1 of the declared BPMN process 'loan_approval'"),
        () -> "the version which could not be wired has to be named: "
            + logged);
    assertTrue(
        logged.contains("incident"),
        () -> "and what a workflow on that version walks into: "
            + logged);
    assertTrue(
        taskRegistry.resolve(MODULE, OLD_ID, "Activity_approve", "approve").isPresent(),
        "the version which could be read is wired regardless");

  }

  /**
   * An adapter whose core declares one BPMN process id without a model, and whose engine
   * holds the given models under it, keyed by version.
   */
  private static Camunda7DeploymentService adapterServing(
      final Camunda7TaskRegistry taskRegistry,
      final Map<String, String> modelsByVersion) {

    final var core = mock(WorkflowTaskWiring.class);
    when(core.taskWiringOfProcessesNobodyDeployed(MODULE))
        .thenReturn(Map.of(OLD_ID, List.<String>of("checkCredit", "approve")));
    return new Camunda7DeploymentService(
        "c7", anEngineHolding(modelsByVersion), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(core)
            .build(), taskRegistry);

  }

  /**
   * A repository service answering with the given versions of the declared process and
   * with their models.
   */
  private static RepositoryService anEngineHolding(
      final Map<String, String> modelsByVersion) {

    // every mock is built BEFORE the first stubbing: creating one between when() and
    // thenReturn() leaves Mockito with a stubbing it considers unfinished
    final var definitions = definitions(modelsByVersion.keySet());
    final var repositoryService = mock(RepositoryService.class);
    final var query = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
    Mockito.lenient().when(query.list()).thenReturn(definitions);
    when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
    modelsByVersion
        .forEach((
            version,
            model) -> Mockito
                .lenient()
                .when(repositoryService.getBpmnModelInstance("definition-"
                    + version))
                .thenReturn(
                    Bpmn.readModelFromStream(new ByteArrayInputStream(model.getBytes(StandardCharsets.UTF_8)))));
    return repositoryService;

  }

  private static List<ProcessDefinition> definitions(
      final Collection<String> versions) {

    return versions
        .stream()
        .map(version -> {
          final var definition = mock(ProcessDefinition.class);
          Mockito.lenient().when(definition.getId()).thenReturn("definition-"
              + version);
          Mockito.lenient().when(definition.getVersion()).thenReturn(Integer.valueOf(version));
          Mockito.lenient().when(definition.getKey()).thenReturn(OLD_ID);
          return definition;
        })
        .map(ProcessDefinition.class::cast)
        .toList();

  }

}
