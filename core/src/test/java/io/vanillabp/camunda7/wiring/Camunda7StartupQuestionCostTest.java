package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter asks the engine while an application boots, counted.
 * <p>
 * The startup check for old process versions asks the catalog two things about every
 * version older than the one the boot deployed: the model of that version and how many
 * workflows still run on it. Both need the engine's process definition id, and looking
 * that up used to be a definition query of its own - so a process with fifty versions
 * behind it paid a hundred queries nobody could see, on top of the one list which had
 * already brought every id back.
 * <p>
 * Decision 10 in the repository's DECISIONS.md is what this holds: an engine query while
 * booting is counted, and the count belongs to the versions, never to the workflows.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StartupQuestionCostTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * How many versions the engine holds - enough that a query per version is a different
   * number from a query per process.
   */
  private static final int VERSIONS = 4;

  private RepositoryService repositoryService;

  private RuntimeService runtimeService;

  private Camunda7ProcessVersions versions;

  /**
   * Every query the engine handed out, by kind.
   */
  private final java.util.Map<String, Integer> queries = new java.util.TreeMap<>();

  private ProcessDefinition definition(
      final int version) {

    final var definition = Mockito.mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getId())
        .thenReturn("%s:%d:key".formatted(PROCESS, version));
    Mockito
        .lenient()
        .when(definition.getVersion())
        .thenReturn(version);
    return definition;

  }

  @BeforeEach
  public void setUp() {

    final var definitions = java.util.stream.IntStream
        .rangeClosed(1, VERSIONS)
        .mapToObj(this::definition)
        .toList();

    final var definitionQuery = Mockito.mock(ProcessDefinitionQuery.class, Mockito.RETURNS_SELF);
    Mockito
        .lenient()
        .when(definitionQuery.list())
        .thenReturn(definitions);
    // the engine answers with the version it was asked for, whether or not the list above
    // held it: a version deployed by another node after the list was read is exactly the
    // case the second test is about
    final var askedFor = new int[]{
        1
    };
    Mockito
        .lenient()
        .when(definitionQuery.processDefinitionVersion(Mockito.anyInt()))
        .thenAnswer(invocation -> {
          askedFor[0] = invocation.getArgument(0);
          return definitionQuery;
        });
    Mockito
        .lenient()
        .when(definitionQuery.singleResult())
        .thenAnswer(invocation -> definition(askedFor[0]));

    final var instanceQuery = Mockito.mock(ProcessInstanceQuery.class, Mockito.RETURNS_SELF);

    repositoryService = Mockito.mock(RepositoryService.class);
    Mockito
        .lenient()
        .when(repositoryService.createProcessDefinitionQuery())
        .thenAnswer(invocation -> {
          queries.merge("createProcessDefinitionQuery", 1, Integer::sum);
          return definitionQuery;
        });
    Mockito
        .lenient()
        .when(repositoryService.getBpmnModelInstance(Mockito.anyString()))
        .thenAnswer(invocation -> {
          queries.merge("getBpmnModelInstance", 1, Integer::sum);
          return null;
        });

    runtimeService = Mockito.mock(RuntimeService.class);
    Mockito
        .lenient()
        .when(runtimeService.createProcessInstanceQuery())
        .thenAnswer(invocation -> {
          queries.merge("createProcessInstanceQuery", 1, Integer::sum);
          return instanceQuery;
        });

    versions = new Camunda7ProcessVersions(
        "c7", repositoryService, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null, (
                workflowModuleId,
                bpmnProcessId,
                version,
                model) -> List.of());
    versions.setRuntimeService(runtimeService);
    queries.clear();

  }

  /**
   * What the startup check does per BPMN process: it asks for the versions once and then
   * asks two questions about every older one.
   */
  private void whatAStartAsks() {

    final var deployed = versions.deployedVersionsOf(MODULE, PROCESS);
    deployed
        .stream()
        .map(io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion::version)
        .filter(version -> !String.valueOf(VERSIONS).equals(version))
        .forEach(version -> {
          versions.activeInstanceCountOf(MODULE, PROCESS, version);
          versions.tasksOfVersion(MODULE, PROCESS, version);
        });

  }

  @Test
  @DisplayName("The version list is the only definition query a start needs")
  public void theVersionListAnswersEveryLaterQuestion() {

    whatAStartAsks();

    assertEquals(
        1,
        queries.getOrDefault("createProcessDefinitionQuery", 0),
        () -> "one list of definitions holds every id the later questions need, but was "
            + queries);
    assertEquals(
        VERSIONS - 1,
        queries.getOrDefault("createProcessInstanceQuery", 0),
        () -> "one count per version older than the deployed one, but was "
            + queries);
    assertEquals(
        VERSIONS - 1,
        queries.getOrDefault("getBpmnModelInstance", 0),
        () -> "one model per version older than the deployed one, but was "
            + queries);

  }

  @Test
  @DisplayName("A version the list did not hold is looked up once, not once per question")
  public void aVersionDeployedLaterIsLookedUpOnce() {

    // a version another cluster node deployed after the list was read: the engine has to
    // be asked for it, and asked once
    versions.activeInstanceCountOf(MODULE, PROCESS, "7");
    versions.tasksOfVersion(MODULE, PROCESS, "7");

    assertEquals(
        1,
        queries.getOrDefault("createProcessDefinitionQuery", 0),
        () -> "the second question is answered from what the first one learned, but was "
            + queries);

  }

}
