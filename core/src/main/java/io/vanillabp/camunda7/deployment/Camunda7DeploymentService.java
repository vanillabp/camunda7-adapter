package io.vanillabp.camunda7.deployment;

import java.io.InputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.engine.Camunda7InstanceIdentity;
import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterPlatformVersion;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 7 implementation of the VanillaBP adapter deployment SPI. One instance exists
 * per configured adapter id (not per adapter type).
 * <p>
 * The BPMN files of a workflow module are read into Camunda's own
 * {@link BpmnModelInstance} model, accumulated in a {@link Camunda7ProcessingContext} and
 * finally deployed as a single Camunda deployment. Whether a module is isolated by a
 * Camunda TENANT named after it (version 1's behavior), by prefixed identifiers or not at
 * all is the name-clash-avoidance mode's decision, {@code none} being this adapter's
 * default; duplicate filtering is enabled so unchanged models are not redeployed on every
 * boot.
 * <p>
 * Task wiring ({@link #wireBpmn}) extracts the service-like tasks from the model, validates
 * them against the registered {@code @WorkflowTask} methods (both directions, guiding
 * messages) and registers the connectables with the engine's EL resolver - the engine then
 * hands the model to the core's {@code WorkflowTaskWiring}.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda7DeploymentService implements AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext> {

  /**
   * The adapter type of the Camunda 7 adapter. There may be several adapter ids of this
   * type configured (e.g. two Camunda 7 engines side by side during a migration).
   */
  public static final String ADAPTER_TYPE = io.vanillabp.camunda7.Camunda7Adapter.ADAPTER_TYPE;

  private final String adapterId;

  /**
   * The embedded engine's repository service used to deploy BPMN resources. Provided by
   * the platform module (Spring Boot) which wires the embedded engine sharing the
   * application's data source and transaction manager.
   */
  private final RepositoryService repositoryService;

  /**
   * Controls the engine's job executor: activation is deferred to
   * {@link #startWorkflowProcessing} (the executor is engine-global - the platform's
   * implementation reference-counts the started modules and stops the executor only
   * when the last module stops, see {@link Camunda7WorkflowProcessingLifecycle}).
   */
  private final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle;

  /**
   * The core's task-processing entry point: wiring validation during
   * {@link #wireBpmn} and task dispatch at runtime (via the EL resolver).
   */
  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  /**
   * The task connectables of this adapter id's engine, registered during
   * {@link #wireBpmn} and looked up by the engine's EL resolver.
   */
  private final Camunda7TaskRegistry taskRegistry;

  /**
   * The core's entry point for workflows the engine starts on its own:
   * the start events of a process are reported here while wiring. May be
   * <code>null</code> (tests) - nothing is reported then.
   */
  private final io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * The core's registry of <code>&#64;WorkflowEnded</code> methods, used at deployment
   * to tell an application that its method will never be called. May be
   * <code>null</code> (tests) - nothing is checked then.
   */
  private final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * Whether the engine of this adapter id attached the end listener, see
   * {@link #setWorkflowEndedSupport}.
   */
  private boolean engineDeliversWorkflowEnded;

  /**
   * Says whether the engine of this adapter id attached the end listener - the half of
   * the end support which is this adapter's own business. The core's registry of
   * <code>&#64;WorkflowEnded</code> methods arrives with the collaborators.
   *
   * @param engineDeliversWorkflowEnded Whether the engine attached its end listener
   */
  public void setEngineDeliversWorkflowEnded(
      final boolean engineDeliversWorkflowEnded) {

    this.engineDeliversWorkflowEnded = engineDeliversWorkflowEnded;

  }

  /**
   * The core's name-clash-avoidance model: decides whether a workflow
   * module is isolated by the Camunda TENANT ({@code by-adapter}, version 1's
   * behavior), by PREFIXING the identifiers ({@code use-prefix} - no tenant) or not at
   * all ({@code none}, this adapter's default). May be <code>null</code> (tests).
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * The engine's identity service, used to tell whether a tenant deployed into is
   * REGISTERED there (see {@link Camunda7TenantCheck}). May be <code>null</code> (tests,
   * or a platform not handing it over): the check is skipped then.
   */
  private org.camunda.bpm.engine.IdentityService identityService;

  /**
   * Whether the configured tenant was already checked against the mode (once per
   * adapter instance, the check is adapter-wide).
   */
  private boolean tenantConfigurationValidated;

  /**
   * Whether the application accepted unscoped identifiers deliberately
   * (<code>vanillabp.adapters.&lt;id&gt;.accept-unscoped-identifiers</code>), which
   * silences {@link #warnAboutUnscopedIdentifiers(String, boolean)}.
   */
  private boolean acceptUnscopedIdentifiers;

  /**
   * Sets the acknowledgement that identifiers are unique across workflow modules (the
   * platform modules read it from the adapter's configuration).
   *
   * @param acceptUnscopedIdentifiers Whether unscoped identifiers are accepted
   */
  public void setAcceptUnscopedIdentifiers(
      final boolean acceptUnscopedIdentifiers) {

    this.acceptUnscopedIdentifiers = acceptUnscopedIdentifiers;

  }

  /**
   * The tenant name configured for this adapter id
   * (<code>vanillabp.adapters.&lt;id&gt;.tenant-id</code>) or <code>null</code> - then
   * the workflow module ID names the tenant (VanillaBP 1's behavior).
   */
  private String configuredTenantId;

  /**
   * Sets the configured tenant name (the platform modules read it from the adapter's
   * configuration).
   *
   * @param configuredTenantId The tenant name or <code>null</code>
   */
  public void setConfiguredTenantId(
      final String configuredTenantId) {

    this.configuredTenantId = configuredTenantId;

  }

  /**
   * Sets the engine's identity service (the platform modules take it from this adapter
   * id's engine).
   *
   * @param identityService The identity service or <code>null</code>
   */
  public void setIdentityService(
      final org.camunda.bpm.engine.IdentityService identityService) {

    this.identityService = identityService;

  }

  /**
   * The BPMN process id as the ENGINE knows it (prefixed when the module's mode is
   * {@code use-prefix}).
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * What this engine holds for a BPMN process this application declares without deploying
   * a model under it - the old id of a renamed process, which the engine keeps with every
   * version ever deployed under it and with the workflows still running on them.
   * <p>
   * It is the same catalog every deployed process of this adapter is registered with: it
   * queries the definitions by the process key as the ENGINE knows it, so a prefix and a
   * tenant reach the old id like any other.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog processVersionCatalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    // being asked about a declared id is the FIRST thing which happens to it, and
    // whatever reads the catalog next makes the engine PARSE the definitions the id
    // still has - the moment the parse listener decides, by exactly this
    // registration, whether the end of such a workflow is reported. Registering any
    // later loses the end listener for good, because a parsed definition stays
    // cached (measured by Camunda7DeclaredIdRuntimeIT). May be null in tests
    if (taskRegistry != null) {
      taskRegistry
          .registerProcess(workflowModuleId, bpmnProcessId, scopedProcessId(workflowModuleId, bpmnProcessId));
    }
    return processVersions;

  }

  /**
   * Fails the boot if a tenant is configured for this adapter id although no workflow
   * module is deployed into one, i.e. the mode says {@code none} or {@code use-prefix}
   * everywhere. Whether a tenant is what only {@code by-adapter} can use is this
   * adapter's knowledge; the core answers which modes apply. Checked once per adapter
   * instance while deploying, before anything reaches the engine.
   */
  private void validateTenantConfiguration() {

    if (tenantConfigurationValidated || (scoping == null)) {
      return;
    }
    tenantConfigurationValidated = true;
    if ((configuredTenantId == null) || configuredTenantId.isBlank()) {
      return;
    }
    scoping.validateNoneNameClashStrategy(
        adapterId,
        "vanillabp.adapters.%s.tenant-id".formatted(adapterId));

  }

  /**
   * The Camunda tenant a workflow module is deployed to - the module id under
   * {@code by-adapter}, none under {@code use-prefix}/{@code none}.
   */
  private String tenantIdOf(
      final String workflowModuleId) {

    return io.vanillabp.camunda7.wiring.Camunda7Scoping
        .tenantIdFor(scoping, workflowModuleId, adapterId, configuredTenantId);

  }

  /**
   * The namespace of Camunda's BPMN extension attributes. Kept as ONE constant and
   * read namespace-generically ({@code getAttributeValueNs}) - fork portability
   * (Operaton/CIB seven renamed the typed extension getters, the attribute
   * namespace is accepted by both).
   */
  public static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

  private static final Pattern EL_PATTERN = Pattern.compile("^[#$]\\{([^}]+)}$");

  /**
   * Resolves what makes an adapter id a DISTINCT engine (datasource and table
   * prefix) - platform-supplied, used by
   * {@link #validateDistinctAdapterInstances(List)}. May be <code>null</code>
   * (tests): the check is skipped then.
   */
  private final java.util.function.Function<String, Camunda7InstanceIdentity> instanceIdentities;

  /**
   * Convenience constructor without the instance-identity resolver (tests) - two
   * adapter ids of this type are not checked for distinctness then.
   */
  public Camunda7DeploymentService(
      final String adapterId,
      final RepositoryService repositoryService,
      final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle,
      final AdapterCollaborators collaborators,
      final Camunda7TaskRegistry taskRegistry) {

    this(adapterId, repositoryService, workflowProcessingLifecycle, collaborators, taskRegistry, null);

  }

  public Camunda7DeploymentService(
      final String adapterId,
      final RepositoryService repositoryService,
      final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle,
      final AdapterCollaborators collaborators,
      final Camunda7TaskRegistry taskRegistry,
      final java.util.function.Function<String, Camunda7InstanceIdentity> instanceIdentities) {

    AdapterPlatformVersion.requireCompatiblePlatform(ADAPTER_TYPE, Camunda7DeploymentService.class);

    this.adapterId = adapterId;
    this.repositoryService = repositoryService;
    this.workflowProcessingLifecycle = workflowProcessingLifecycle;
    this.collaborators = collaborators;
    this.workflowTaskWiring = collaborators.workflowTaskWiring();
    this.bpmsInitiatedStartInvoker = collaborators.bpmsInitiatedStartInvoker().orElse(null);
    this.workflowEndedInvoker = collaborators.workflowEndedInvoker().orElse(null);
    this.scoping = collaborators.scoping();
    this.taskRegistry = taskRegistry;
    this.instanceIdentities = instanceIdentities;
    // What the engine's process definitions are versioned as - the
    // registry hands it to every listener building an invocation context
    this.processVersions = new io.vanillabp.camunda7.wiring.Camunda7ProcessVersions(
        adapterId, repositoryService, this::scopedProcessId, this::tenantIdOf, new HeldModels());
    // the emergency exit past the old-versions check is meant to be the decision of THIS
    // start, so every start says out loud that it was taken
    io.vanillabp.camunda7.wiring.SuspendedProcessDefinitions.reportIfTheSwitchIsSet(adapterId);
    if (taskRegistry != null) {
      taskRegistry.setProcessVersions(processVersions);
      // every inbound delivery reports which adapter it came from
      taskRegistry.setAdapterId(adapterId);
      // the EL resolver builds the task behavior and needs both to raise a BPMN error
      // the deployed model still carries; it cannot be handed anything itself
      taskRegistry.setScoping(scoping);
    }

  }

  /**
   * The versions of this engine's process definitions: the source of the
   * version reported with every task, start and end, and the catalog the core resolves
   * version TAGS through.
   */
  private final io.vanillabp.camunda7.wiring.Camunda7ProcessVersions processVersions;

  /**
   * Two <code>camunda7</code> adapter ids are only distinct engines if they run on
   * different databases: an own datasource, or an own table prefix on a shared one
   * (see {@link Camunda7InstanceIdentity}).
   */
  @Override
  public void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

    Camunda7InstanceIdentity.validateDistinct(adapterIdsOfThisType, instanceIdentities);

  }

  /**
   * Camunda 7 keeps the SPI's default, {@code by-adapter}, which on this engine means a
   * tenant named after the workflow module: exactly what VanillaBP 1 deployed when
   * nothing was configured. An application upgrading from version 1 without touching
   * its configuration therefore finds the workflows it started back then, and that is
   * the whole reason this is not a decision the adapter makes for itself.
   * <p>
   * It costs nothing on this engine: a tenant id is an attribute of the deployment, so
   * the engine accepts any name and creates nothing (see {@link Camunda7TenantCheck}).
   * The alternatives are named where they matter - by
   * {@link #warnAboutUnscopedIdentifiers(String, boolean)} once a module runs
   * unscoped, and by the wiki for an application which would rather prefix its
   * identifiers or give a module an engine of its own.
   * <p>
   * Held by {@code Camunda7DeploymentServiceTest}, which asserted {@code none}
   * between 2026-08-11 and 2026-08-22 - that was the defect.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.NameClashAvoidance defaultNameClashAvoidance() {

    return io.vanillabp.integration.adapter.spi.NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Names what Camunda 7 offers instead of {@code none}: a tenant per workflow module
   * (the engine is multi-tenant out of the box), prefixing, or an engine per workflow
   * module - an own datasource respectively an own table prefix on a shared one.
   * <p>
   * Silent if the application accepted unscoped identifiers deliberately
   * ({@code vanillabp.adapters.<id>.accept-unscoped-identifiers}) - the point of the
   * warning is the DECISION, and once it is on record there is nothing left to ask.
   */
  @Override
  public void warnAboutUnscopedIdentifiers(
      final String workflowModuleId,
      final boolean fromDefault) {

    if (acceptUnscopedIdentifiers) {
      log.debug(
          "Camunda7[{}]: workflow module '{}' is deployed with name-clash-avoidance 'none', accepted by "
              + "'vanillabp.adapters.{}.accept-unscoped-identifiers'",
          adapterId,
          workflowModuleId,
          adapterId);
      return;
    }
    log.warn(
        """
            Workflow module '{}' is deployed to Camunda 7 (adapter '{}') with name-clash-avoidance \
            'none'{}. Its identifiers reach the engine as they are - BPMN process ids, message and \
            signal names, error codes and task definitions - so a second workflow module using the \
            same identifier addresses the very same process definitions and tasks, and neither \
            VanillaBP nor the engine can tell. Keep 'none' only as long as your identifiers are \
            unique across ALL workflow modules of this application. Otherwise choose:
              vanillabp.adapters.{}.name-clash-avoidance: by-adapter   # a tenant per workflow module, Camunda 7's own isolation
              vanillabp.adapters.{}.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers, no tenant needed
            A third option is an engine per workflow module, configured as one adapter id per engine \
            with its own database ('vanillabp.adapters.<id>.data-source-name') respectively its own \
            tables in a shared one ('vanillabp.adapters.<id>.table-prefix'). The same key may be set \
            per workflow module (vanillabp.workflow-modules.{}.adapters.{}.name-clash-avoidance). The \
            mode is not a runtime switch - changing it once workflows are running is a BPMS \
            migration. If the identifiers ARE unique, say so once and this warning is gone:
              vanillabp.adapters.{}.accept-unscoped-identifiers: true""",
        workflowModuleId,
        adapterId,
        fromDefault
            ? " (nothing is configured, so the adapter's default applies)"
            : "",
        adapterId,
        adapterId,
        workflowModuleId,
        adapterId,
        adapterId);

  }

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
    // Rewrite the identifiers the engine resolves across process
    // definitions BEFORE wiring - a no-op unless the mode is 'use-prefix'. The core
    // calls prepareBpmn once per executable PROCESS while all processes of a file
    // share ONE model, so scoping has to happen once per FILE - otherwise a
    // multi-process file would collect one prefix per process.
    final var modelAlreadyScoped = context
        .getResourcesByFilename()
        .containsKey(filename);
    if (!modelAlreadyScoped) {
      // A call activity of this engine does not pass the business key -
      // which holds the workflow aggregate's ID - unless the model says so. Injected
      // BEFORE scoping, which rewrites the called elements: here the process IDs are
      // still the ones the application knows
      io.vanillabp.camunda7.wiring.Camunda7CallActivities
          .propagateBusinessKey(model, workflowModuleId, workflowTaskWiring);
      io.vanillabp.camunda7.wiring.Camunda7Scoping.apply(model, workflowModuleId, adapterId, scoping);
    }
    context.addResource(filename, model);
    context.recordDeployedProcess(bpmnProcessId);
    return context;

  }

  @Override
  public Camunda7ProcessingContext readDmn(
      final String workflowModuleId,
      final Camunda7ProcessingContext existingContext,
      final String filename,
      final java.io.InputStream dmn) {

    // the decision travels as bytes: the engine reads it, this adapter only has to make
    // sure the id it is deployed under matches what the business rule task points at
    final var file = io.vanillabp.integration.adapter.spi.DmnDecisionIds.bytesOf(dmn);
    final var prefixes = io.vanillabp.camunda7.wiring.Camunda7Scoping
        .prefixes(workflowModuleId, adapterId, scoping);
    final var toDeploy = prefixes
        ? io.vanillabp.integration.adapter.spi.DmnDecisionIds
            .rewrite(file, id -> scoping.scopedIdentifier(workflowModuleId, id, adapterId))
        : file;
    if (prefixes) {
      log.debug(
          "Camunda7[{}]: the decisions of '{}' are deployed under prefixed ids ({}), matching the "
              + "'camunda:decisionRef' of the business rule tasks calling them",
          adapterId,
          filename,
          io.vanillabp.integration.adapter.spi.DmnDecisionIds.of(toDeploy));
    }
    existingContext.addDecision(filename, toDeploy);
    return existingContext;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda7ProcessingContext context) {

    // extract the service-like tasks of THIS process from the model: VanillaBP's
    // Camunda 7 convention wires tasks by 'camunda:expression' (handler runs while
    // the expression evaluates) or 'camunda:delegateExpression' (@TaskId tasks can
    // stay open) - the unwrapped expression text is the task definition
    final var specs = new LinkedList<BpmnTaskSpec>();
    final var connectables = new LinkedList<Camunda7TaskConnectable>();
    // the model carries the identifiers the ENGINE will know (prepareBpmn rewrote
    // them), while the core is keyed by the plain ones - so the model is searched
    // by the scoped id and the invoker is called with the plain one
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    collectTasks(
        model,
        workflowModuleId,
        bpmnProcessId,
        scopedBpmnProcessId,
        "file '%s'".formatted(filename),
        specs,
        connectables);

    // both directions with guiding messages; throwing here honors the
    // deployment-failure policy for non-first-priority adapter ids
    workflowTaskWiring.validateTaskWiring(workflowModuleId, bpmnProcessId, specs);

    // What follows judges the model this boot brings, and the checks whose finding a
    // modeller can still act on belong here and nowhere else: an asynchronous task wired
    // by expression and an expression reading what the aggregate does not share are both
    // answers to something which can be changed and deployed again. The findings which
    // outlive a deployment are asked of the version catalog instead, over the models the
    // engine holds, because a workflow started years ago runs into them just the same
    // and nobody can go back and change the model it is on.

    // A task wired by 'camunda:expression' completes as soon as the
    // expression returns, so a method declaring @TaskId can never keep it open.
    // The engine's EL resolver says the same at runtime, but only once a workflow
    // reaches the task - asking the core here moves the verdict to the boot. The
    // reverse case needs no message: 'camunda:delegateExpression' serves a method
    // without @TaskId just as well, the behavior leaves the activity when the
    // handler returns.
    connectables
        .stream()
        .filter(connectable -> connectable.type() == Camunda7TaskConnectable.Type.EXPRESSION)
        .filter(connectable -> workflowTaskWiring.workflowTaskCompletesAsynchronously(
            workflowModuleId,
            bpmnProcessId,
            connectable.taskDefinition()))
        .findFirst()
        .ifPresent(connectable -> {
          throw new IllegalStateException(
              Camunda7TaskConnectable.asynchronousTaskWiredByExpression(
                  connectable.taskDefinition(),
                  bpmnProcessId,
                  workflowModuleId));
        });

    connectables.forEach(taskRegistry::register);

    // a process the engine starts on its own may have no tasks at all, so the way
    // back from the engine's process-definition key is registered explicitly
    taskRegistry.registerProcess(workflowModuleId, bpmnProcessId, scopedBpmnProcessId);

    // The engine can be asked which versions of this process it has, which
    // is what a version specification naming a version TAG needs
    workflowTaskWiring
        .registerProcessVersions(adapterId, workflowModuleId, bpmnProcessId, processVersions);

    // Which elements can put a second token into a running workflow - two
    // tokens are two writers on the workflow aggregate, and the core knows whether
    // that aggregate can survive them
    workflowTaskWiring
        .reportConcurrentTokenElements(
            workflowModuleId,
            bpmnProcessId,
            Camunda7ConcurrentTokens.elementIdsOf(model, scopedBpmnProcessId));

    // What the expressions of this model read, which two checks ask the core about. The
    // model is parsed for them once: both questions are about the same paths
    final var expressionOrigins = io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers
        .of(model, scopedBpmnProcessId);

    // An expression reading an attribute the aggregate does not share
    // evaluates to null, and Camunda 7 then takes the default flow without saying a
    // word. The adapter knows the model, the core knows what is shared - together they
    // can say it while the application starts
    warnAboutUnsharedAggregatePaths(workflowModuleId, bpmnProcessId, expressionOrigins);

    // And a value which IS shared may still reach the expression as something else than
    // the application holds, because a value the engine has no type for travels through
    // the configured serialization format
    warnAboutTypesTheFormatCannotCarry(workflowModuleId, bpmnProcessId, expressionOrigins);

    // This engine reports the end of a workflow, so a @WorkflowEnded
    // method staying silent means the adapter was not wired - which used to be
    // invisible: the application booted, the workflow ran, the method was never
    // called and nothing was logged. The same is asked for a declared id in
    // wireTheVersionsHeldUnder, where no model of this boot passes by
    warnAboutUnservedWorkflowEndedHandlers(workflowModuleId, bpmnProcessId);

    wireBpmsInitiatedStarts(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);

    log.info(
        "Camunda7[{}]: wired {} task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
        adapterId,
        connectables.size(),
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  /**
   * The engine's runtime service, used by the startup check to ask how
   * many workflows still run on an old version of a process. Set by the platform
   * integration, like the identity service.
   *
   * @param runtimeService The engine's runtime service
   */
  public void setRuntimeService(
      final org.camunda.bpm.engine.RuntimeService runtimeService) {

    this.runtimeService = runtimeService;
    processVersions.setRuntimeService(runtimeService);

  }

  /**
   * The engine's runtime service, kept here as well so the startup can ask WHERE the
   * workflows of a process run. May be <code>null</code> in tests.
   */
  private org.camunda.bpm.engine.RuntimeService runtimeService;

  /**
   * How the serialization format of a workflow is resolved, which the startup check needs
   * because a workflow may deviate from its module and a module from the adapter. Set by
   * the platform integration, like the identity service. May be <code>null</code> (tests):
   * nothing is then reported about a format.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats;

  /**
   * Sets the format resolution of the platform integration.
   *
   * @param serializationFormats The format per workflow, module and adapter
   */
  public void setSerializationFormats(
      final io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats) {

    this.serializationFormats = serializationFormats;

  }

  /**
   * What a configured format does to a value, measured through this engine's own
   * serializers. May be <code>null</code> (tests, or an engine which does not hand its
   * configuration over): nothing is reported then.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip serializationRoundTrip;

  /**
   * Sets the probe reading this engine's serializers.
   *
   * @param serializationRoundTrip The probe, or <code>null</code>
   */
  public void setSerializationRoundTrip(
      final io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip serializationRoundTrip) {

    this.serializationRoundTrip = serializationRoundTrip;

  }

  /**
   * The process definition the engine considers current for that process - what this
   * application runs on when its resources were deployed before.
   */
  private org.camunda.bpm.engine.repository.ProcessDefinition latestVersionOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var tenantId = tenantIdOf(workflowModuleId);
    var query = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(scopedProcessId(workflowModuleId, bpmnProcessId))
        .latestVersion();
    query = tenantId == null
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId);
    return query.singleResult();

  }

  /**
   * What this adapter's extraction says about a model the engine still holds, handed to
   * the version catalog so its questions about an old version are answered by the walks a
   * deployed model goes through.
   */
  private final class HeldModels implements io.vanillabp.camunda7.wiring.Camunda7ProcessVersions.HeldModelReading {

    @Override
    public java.util.Collection<BpmnTaskSpec> tasksOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return tasksOfDeployedModel(workflowModuleId, bpmnProcessId, version, model);

    }

    @Override
    public java.util.Collection<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return startEventsOfHeldModel(workflowModuleId, bpmnProcessId, version, model);

    }

    @Override
    public java.util.Collection<String> concurrentTokenElementsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return concurrentTokenElementsOfHeldModel(workflowModuleId, bpmnProcessId, model);

    }

  }

  /**
   * The elements of a model the engine still holds which can put a SECOND token into one of
   * its workflows - the same walk this adapter reports to the core while wiring, run over an
   * old version.
   * <p>
   * The versions which run longest are the ones a look at this boot's model never reaches: a
   * parallel gateway the newest model dropped keeps forking every workflow started before
   * it, and two branches writing one workflow aggregate lose an update there exactly as they
   * would in the model just deployed.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param model The model of that version
   * @return The IDs of the elements producing a second token in that version
   */
  private java.util.Collection<String> concurrentTokenElementsOfHeldModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    return Camunda7ConcurrentTokens
        .elementIdsOf(model, scopedProcessId(workflowModuleId, bpmnProcessId));

  }

  /**
   * The tasks of a model the engine still holds, read for the old-versions startup
   * check - the same extraction the deployed model goes through, so both directions
   * cannot disagree about what a task is.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the engine assigned
   * @param model The model of that version
   * @return The tasks of that version
   */
  private java.util.Collection<BpmnTaskSpec> tasksOfDeployedModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    final var specs = new LinkedList<BpmnTaskSpec>();
    collectTasks(
        model,
        workflowModuleId,
        bpmnProcessId,
        scopedProcessId(workflowModuleId, bpmnProcessId),
        "version %s".formatted(version),
        specs,
        null);
    return specs;

  }

  /**
   * Collects the connectables of ONE version the engine holds under a declared BPMN process
   * id, keyed so that a task several versions share is registered once.
   * <p>
   * A model the extraction refuses is skipped rather than allowed to end the boot. Refusing
   * one is what a model wired by <code>camunda:topic</code> or by an expression VanillaBP
   * does not understand gets, and the deployment of a MODEL THIS BOOT BRINGS should end over
   * it - that is the same check. This model was deployed by an earlier generation of the
   * application and nobody can change it any more, so the honest answer is one warning about
   * the version which cannot be wired, while the versions which can are.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID nothing was deployed under
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param version The version the engine assigned
   * @param definitionId The engine's process definition id of that version
   * @param distinctConnectables Collects the connectables, by element, task definition and
   *          type
   */
  private void wireTheModelOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String version,
      final String definitionId,
      final Map<String, Camunda7TaskConnectable> distinctConnectables) {

    final var specs = new LinkedList<BpmnTaskSpec>();
    final var connectables = new LinkedList<Camunda7TaskConnectable>();
    try {
      final var model = repositoryService.getBpmnModelInstance(definitionId);
      // the start listener attached at parse time asks for the PLAIN signal name of a
      // signal start event, and for a model only the engine holds nobody registered
      // one - it is right here, in the engine's own copy of the model, so a workflow
      // the engine starts under the old id is told which signal fired
      registerSignalStartEventsOf(
          workflowModuleId, scopedBpmnProcessId, startEventsOf(workflowModuleId, scopedBpmnProcessId, model));
      collectTasks(
          model,
          workflowModuleId,
          bpmnProcessId,
          scopedBpmnProcessId,
          "version %s".formatted(version),
          specs,
          connectables);
    } catch (final RuntimeException e) {
      log.warn(
          """
              Camunda7[{}]: version {} of the declared BPMN process '{}' (workflow module '{}') could \
              not be wired, so a workflow still running on THAT version reaches its next task, finds \
              nothing wired to it and ends in an incident. The other versions of that id are wired. \
              Either deploy a model under the old id again until those workflows have ended, or \
              complete them by other means.""",
          adapterId,
          version,
          bpmnProcessId,
          workflowModuleId,
          e);
      return;
    }
    connectables
        .forEach(connectable -> distinctConnectables
            .putIfAbsent(
                "%s|%s|%s".formatted(connectable.elementId(), connectable.taskDefinition(), connectable.type()),
                connectable));

  }

  /**
   * Extracts the tasks of ONE executable BPMN process into the specs the core
   * validates against, and - for the model this boot deploys - into the connectables
   * the engine's EL resolver looks up at runtime.
   * <p>
   * The startup check reads the models of OLDER versions the engine still holds and asks the
   * core whether the application still serves them, which is why this sits in its own
   * method: both directions have to see a model exactly the same way, and a second
   * implementation would drift.
   *
   * @param model The BPMN model, carrying the identifiers the ENGINE knows
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The BPMN process ID as the engine knows it
   * @param describedSource What to name in a message about the model, self-describing
   *          ("file 'x.bpmn'" for the deployed model, "version 3" for one the engine
   *          holds)
   * @param specs Collects the task specs
   * @param connectables Collects the connectables, or <code>null</code> for a model
   *          which is only being READ on behalf of a check: such a model is never
   *          refused - one this boot deploys may be, one the engine already holds is
   *          reported by a warning instead, because nobody can change it any more
   */
  private void collectTasks(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String describedSource,
      final List<BpmnTaskSpec> specs,
      final List<Camunda7TaskConnectable> connectables) {
    serviceLikeTasksOf(model, scopedBpmnProcessId)
        .forEach(task -> {
          final var delegateExpression = task.getAttributeValueNs(CAMUNDA_NS, "delegateExpression");
          final var expression = task.getAttributeValueNs(CAMUNDA_NS, "expression");
          final var topic = task.getAttributeValueNs(CAMUNDA_NS, "topic");
          if ((topic != null) && !topic.isBlank()) {
            if (connectables == null) {
              // the subject of the refusal below is a model being DEPLOYED. This model
              // is only being READ, on behalf of a check about versions the engine
              // already holds, and nobody can change it any more - so the boot goes
              // on, and the check does not see this task
              log.warn(
                  """
                      Camunda7[{}]: task '{}' of BPMN process '{}' ({}, workflow module '{}') is \
                      implemented as an external task (camunda:topic '{}'), which VanillaBP does \
                      not serve. The model is already in the engine, so nothing here can change \
                      that - workflows reaching the task are served by whatever polls the topic, \
                      and the report about older versions says nothing about it.""",
                  adapterId,
                  task.getId(),
                  bpmnProcessId,
                  describedSource,
                  workflowModuleId,
                  topic);
              return;
            }
            throw new IllegalStateException(
                """
                    Task '%s' of BPMN process '%s' (%s, workflow module '%s') is implemented \
                    as an external task (camunda:topic) which is not supported by VanillaBP yet! \
                    Wire the task by 'camunda:expression' or 'camunda:delegateExpression' naming the \
                    @WorkflowTask method's task definition, e.g. ${%s}."""
                    .formatted(task.getId(), bpmnProcessId, describedSource, workflowModuleId, topic));
          }
          // a business rule task calling a DECISION is served by the engine, not by the
          // application: the decision was deployed with this process, and asking for a
          // @WorkflowTask method would make DMN unusable on this adapter. A business
          // rule task wired by an expression is an ordinary VanillaBP task and falls
          // through to the branches below
          final var decisionRef = task.getAttributeValueNs(CAMUNDA_NS, "decisionRef");
          if ((decisionRef != null) && !decisionRef.isBlank()) {
            return;
          }
          final String rawExpression;
          final Camunda7TaskConnectable.Type type;
          if ((delegateExpression != null) && !delegateExpression.isBlank()) {
            rawExpression = delegateExpression;
            type = Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION;
          } else if ((expression != null) && !expression.isBlank()) {
            rawExpression = expression;
            type = Camunda7TaskConnectable.Type.EXPRESSION;
          } else {
            // no implementation given: reported by the wiring validation with a
            // guiding message (task definition null - matched by activity ID only)
            specs.add(new BpmnTaskSpec(task.getId(), null));
            return;
          }
          final String taskDefinition;
          if (connectables == null) {
            // a model only being READ: an expression VanillaBP cannot read costs the
            // check its view of THIS task, never the boot
            try {
              taskDefinition = unwrapExpression(
                  rawExpression, task.getId(), bpmnProcessId, describedSource, workflowModuleId);
            } catch (final IllegalStateException e) {
              log.warn(
                  """
                      Camunda7[{}]: the expression '{}' of task '{}' of BPMN process '{}' ({}, \
                      workflow module '{}') cannot be read by VanillaBP. The model is already in \
                      the engine, so nothing here can change that - the report about older \
                      versions says nothing about this task.""",
                  adapterId,
                  rawExpression,
                  task.getId(),
                  bpmnProcessId,
                  describedSource,
                  workflowModuleId);
              return;
            }
          } else {
            taskDefinition = unwrapExpression(
                rawExpression, task.getId(), bpmnProcessId, describedSource, workflowModuleId);
          }
          specs.add(new BpmnTaskSpec(task.getId(), taskDefinition));
          if (connectables != null) {
            connectables.add(new Camunda7TaskConnectable(
                workflowModuleId, bpmnProcessId, scopedBpmnProcessId, task.getId(), taskDefinition, type));
          }
        });

    // user tasks: the task definition is the camunda:formKey; a
    // matching @WorkflowTask method is OPTIONAL (notification only) - the spec
    // still marks matching methods as wired
    model
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.UserTask.class)
        .stream()
        .filter(task -> scopedBpmnProcessId.equals(owningProcessId(task)))
        .forEach(task -> {
          final var formKey = task.getAttributeValueNs(CAMUNDA_NS, "formKey");
          specs.add(BpmnTaskSpec.userTask(task.getId(), formKey));
          if (connectables != null) {
            connectables.add(new Camunda7TaskConnectable(
                workflowModuleId, bpmnProcessId, scopedBpmnProcessId, task
                    .getId(), formKey, Camunda7TaskConnectable.Type.USER_TASK));
          }
        });


  }

  /**
   * The service-like tasks (service, send, business-rule tasks) of the given
   * executable process, including tasks inside embedded subprocesses.
   */
  private static Stream<Task> serviceLikeTasksOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return Stream
        .of(ServiceTask.class, SendTask.class, BusinessRuleTask.class)
        .flatMap(type -> model.getModelElementsByType(type).stream())
        .map(Task.class::cast)
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)));

  }

  static String owningProcessId(
      final FlowElement element) {

    ModelElementInstance current = element;
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  private static String unwrapExpression(
      final String rawExpression,
      final String elementId,
      final String bpmnProcessId,
      final String describedSource,
      final String workflowModuleId) {

    final var matcher = EL_PATTERN.matcher(rawExpression.trim());
    if (!matcher.matches()) {
      throw new IllegalStateException(
          """
              The expression '%s' of task '%s' of BPMN process '%s' (%s, workflow module \
              '%s') is not supported by VanillaBP! Use a simple expression naming the @WorkflowTask \
              method's task definition, e.g. ${myTaskDefinition}."""
              .formatted(rawExpression, elementId, bpmnProcessId, describedSource, workflowModuleId));
    }
    return matcher.group(1).trim();

  }

  /**
   * Reports every expression of the model which reads a path of workflow-aggregate
   * attributes the BPMS is not given, at the segment where the path stops.
   * <p>
   * A WARN, not a failed deployment: the check reads expressions, and an expression it
   * misreads must not keep an application from starting. What it finds is precise enough
   * to act on - the element, the expression, the segment, what the engine will do with
   * the null and the way out - and a model which works produces nothing at all.
   * <p>
   * The severity is the same for every placement, and the SENTENCE is not: a conditional
   * event waits forever without a trace while a timer raises an incident, and which of
   * the two a reader is looking at is what they need to know. A second severity would
   * only invite filtering.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param origins What the expressions of the model read, keyed by the path
   */
  private void warnAboutUnsharedAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Origin> origins) {

    if (origins.isEmpty()) {
      return;
    }
    workflowTaskWiring
        .unsharedWorkflowAggregatePaths(
            workflowModuleId,
            bpmnProcessId,
            origins.keySet(),
            io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE)
        .forEach((
            path,
            verdict) -> reportOneExpression(
                workflowModuleId,
                bpmnProcessId,
                path,
                origins.get(path),
                verdict));

  }

  /**
   * Reports every value the models read whose type the configured serialization format
   * cannot carry unchanged.
   * <p>
   * A value Camunda 7 has no variable type for keeps its class in an object variable, and
   * what an expression reads back is then the serializer's answer. That answer must not
   * depend on the format, and where this adapter cannot prevent that it says so instead
   * of staying quiet. It says it here, while the application boots, rather than leaving it
   * to be found in a rendered form months later.
   * <p>
   * Only what the MODELS read is asked about, the same list the unshared check walks: a
   * value nothing reads costs nobody a wrong decision, and a message about it would be a
   * message nobody can act on. A value which is only rendered into a form or an email is
   * the price of that, and it is not this check's subject.
   * <p>
   * Silence where no format is configured is deliberate. Every one of these types
   * round-trips exactly through Java serialization, so there would be nothing to report,
   * and what an application set on the engine's own
   * <code>defaultSerializationFormat</code> is not something this adapter reads back. Such
   * an application hears about the blob in Cockpit from the missing-format warning
   * instead, which is the other half of the same story.
   * <p>
   * That a value the engine has no type for keeps its class, and that a format which
   * cannot carry it is reported rather than worked around, is decision 16 in the
   * repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param origins What the expressions of the model read, keyed by the path
   */
  private void warnAboutTypesTheFormatCannotCarry(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Origin> origins) {

    if ((serializationRoundTrip == null) || (serializationFormats == null) || origins.isEmpty()) {
      return;
    }
    final var serializationFormat = serializationFormats.formatFor(workflowModuleId, bpmnProcessId);
    if ((serializationFormat == null) || serializationFormat.isBlank()) {
      return;
    }
    workflowTaskWiring
        .declaredTypesOfWorkflowAggregatePaths(
            workflowModuleId,
            bpmnProcessId,
            origins.keySet(),
            io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE)
        .forEach((
            path,
            declaredType) -> serializationRoundTrip
                // a path with a dot in it reaches the engine inside the map the sync
                // model built, which is where a format loses the TYPE rather than a digit
                .whatTheFormatChangesAbout(serializationFormat, declaredType, path.contains("."))
                .ifPresent(whatComesBack -> reportLossyFormat(
                    workflowModuleId,
                    bpmnProcessId,
                    path,
                    declaredType,
                    serializationFormat,
                    whatComesBack)));

  }

  /**
   * One WARN about one value the format changes.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param path The path the expression reads, segments separated by dots
   * @param declaredType The type the application declares that value as
   * @param serializationFormat The format configured for this workflow
   * @param whatComesBack What the measurement found
   */
  private void reportLossyFormat(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String path,
      final Class<?> declaredType,
      final String serializationFormat,
      final io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip.WhatComesBack whatComesBack) {

    log.warn(
        """
            Camunda7[{}]: BPMN process '{}' of workflow module '{}' shares '{}' as a {}, and the \
            serialization format '{}' configured for it cannot carry that type without loss: the \
            engine reads a value of {}. An expression rendering that value, or comparing it for \
            equality, therefore answers something else than your code holds, while a comparison \
            (${amount > 100}) is unaffected, because EL coerces both sides to BigDecimal. Three \
            ways out, pick the one which applies: keep the value out of the BPMS \
            (@NoSyncWithBPMS on its getter) and let the model decide on what your code decided; \
            share it as its text (a getter returning String) where an operator only has to read \
            it; or configure a format which carries the type, at the price the missing-format \
            warning names.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        path,
        declaredType.getName(),
        serializationFormat,
        whatTheFormatMadeOfIt(declaredType, whatComesBack));

  }

  /**
   * What the round trip did to the sample, as the middle of a sentence. The class is named
   * only where it changed, which is what tells a nested value apart from a top-level one:
   * a format which drops a digit is a different problem than a format which drops the
   * type.
   *
   * @param declaredType The type the application declares the value as
   * @param whatComesBack What the measurement found
   * @return A phrase reading "120.50 back as 120.5"
   */
  private static String whatTheFormatMadeOfIt(
      final Class<?> declaredType,
      final io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip.WhatComesBack whatComesBack) {

    if (declaredType.equals(whatComesBack.readBackType())) {
      return "%s back as %s".formatted(whatComesBack.written(), whatComesBack.readBack());
    }
    return "%s back as a %s of %s"
        .formatted(
            whatComesBack.written(),
            whatComesBack
                .readBackType()
                .getName(),
            whatComesBack.readBack());

  }

  /**
   * One WARN about one expression. The top-level case and the path case are two texts and
   * not one with a hole in it, because the last sentence differs in what it PROMISES: the
   * migration fallback of the EL resolver answers a top-level name and stops as soon as
   * something stands before the dot, so a reported path must not be offered it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param path The path the expression reads, segments separated by dots
   * @param origin Where in the model it was read
   * @param verdict What the core's walk found
   */
  private void reportOneExpression(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String path,
      final io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Origin origin,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict verdict) {

    if (verdict.segmentIndex() == 0) {
      log.warn(
          """
              Camunda7[{}]: the expression '{}' of element '{}' (BPMN process '{}' of workflow \
              module '{}') reads '{}', which IS an attribute of the workflow aggregate but is \
              NOT shared with the BPMS, so the engine holds no value of that name. {} Three \
              ways out, pick the one which applies: share the attribute (@SyncWithBPMS on its \
              getter); give it a readable getter if it has none, because the shared values are \
              read from getX() and from isX() returning boolean, never from a field and never \
              from an isX() returning something else (VanillaBP 1 read those, this version \
              does not); or let the expression read something the aggregate does share. Until \
              you do, VanillaBP 2.0 still answers this expression by reading the aggregate \
              directly - version 2.1 removes that fallback.""",
          adapterId,
          origin.expression(),
          origin.elementId(),
          bpmnProcessId,
          workflowModuleId,
          verdict.segment(),
          whatTheEngineDoesWithTheNull(origin.placement()));
      return;
    }
    log.warn(
        """
            Camunda7[{}]: the expression '{}' of element '{}' (BPMN process '{}' of workflow \
            module '{}') reads the path '{}', and the BPMS holds nothing at that path: {} {} \
            Nothing answers this expression in the meantime: the migration fallback of \
            VanillaBP 2.0 reads the workflow aggregate for a top-level name only, so an \
            expression reading past the first dot is already answered with null.""",
        adapterId,
        origin.expression(),
        origin.elementId(),
        bpmnProcessId,
        workflowModuleId,
        path,
        whereThePathStops(verdict),
        whatTheEngineDoesWithTheNull(origin.placement()));

  }

  /**
   * Where the path stops finding anything, and what to do about it. Reads as one or two
   * sentences in the middle of the warning.
   *
   * @param verdict What the core's walk found
   * @return The sentences naming the segment and the way out
   */
  private static String whereThePathStops(
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict verdict) {

    return switch (verdict.kind()) {
      case NOT_SHARED -> """
          '%s' IS a readable attribute of '%s' and is NOT shared with the BPMS. Share it \
          (@SyncWithBPMS on its getter in '%s'), give it a readable getter if it has none, or let \
          the expression read something the aggregate does share.""".formatted(
          verdict.segment(),
          verdict.segmentOwner(),
          verdict.segmentOwner());
      case NO_SUCH_ATTRIBUTE -> """
          '%s' has no readable attribute '%s', so the values it shares carry no such member. \
          Either the model spells the name differently than the aggregate does, or the attribute \
          needs a getter ('%s' shares what getX() and isX() returning boolean answer, never a \
          field).""".formatted(
          verdict.segmentOwner(),
          verdict.segment(),
          verdict.segmentOwner());
      default -> """
          the segment before '%s' is a '%s', which reaches the BPMS as ONE value, a number or a \
          text, and therefore carries nothing below it (an enum arrives as its name, which is a \
          text as well). Let the expression read that value itself, or give the aggregate a \
          getter which answers what the expression wants and share that.""".formatted(
          verdict.segment(),
          verdict.segmentOwner());
    };

  }

  /**
   * What Camunda 7 does with the <code>null</code> such an expression produces, which
   * the placement decides. Every sentence here was measured on an embedded engine
   * 7.24.0; {@code Camunda7NestedExpressionsIT} keeps the silent ones honest.
   *
   * @param placement Where in the model the expression sits
   * @return One sentence about the outcome
   */
  private static String whatTheEngineDoesWithTheNull(
      final io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Placement placement) {

    return switch (placement) {
      case CONDITIONAL_EVENT -> """
          This is the condition of a CONDITIONAL EVENT, the placement Camunda 7 says nothing \
          about at all: the engine answers a condition it cannot evaluate with false, so the \
          event keeps waiting for good, without an incident and without a log line.""";
      case MULTI_INSTANCE_COMPLETION_CONDITION -> """
          This is the completion condition of a multi-instance element: a condition which is \
          never true lets every instance run, so the element ends the way it would without one \
          and nothing says why.""";
      case SEQUENCE_FLOW_CONDITION -> """
          This is a sequence flow condition and the element it leaves declares a default flow, \
          so the workflow quietly continues along that flow.""";
      case SEQUENCE_FLOW_CONDITION_WITHOUT_DEFAULT_FLOW -> """
          This is a sequence flow condition and the element it leaves declares no default flow, \
          so the engine finds no outgoing flow to continue on and raises an incident.""";
      case TIMER -> """
          This is a timer definition, which refuses the null out loud: the engine raises an \
          incident saying the timer was not configured with a valid duration or time.""";
      case MULTI_INSTANCE_CARDINALITY -> """
          This is the cardinality of a multi-instance element, which refuses the null out loud: \
          the engine raises an incident saying the expression has to be a number.""";
      case MULTI_INSTANCE_COLLECTION -> """
          This is the collection of a multi-instance element, which refuses the null out loud: \
          the engine raises an incident saying the expression did not resolve to a collection.""";
    };

  }

  /**
   * Reports a <code>&#64;WorkflowEnded</code> method which this adapter id will never
   * call. Camunda 7 CAN report the end of a workflow, so the only way to get here is a
   * missing wire between the platform module and the engine - it happened
   * once: the Quarkus producer did not hand the invoker over, and nothing said
   * so. The deployment is not failed over it: the workflow itself runs, only the
   * notification is missing.
   * <p>
   * Asked for every process this boot deploys and for every BPMN process id the engine
   * holds versions under while the application only declares it: a workflow of a renamed
   * process ends like any other, and the method kept for the old id is the one nothing
   * else would have spoken about.
   *
   * Visible for tests.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   */
  void warnAboutUnservedWorkflowEndedHandlers(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (engineDeliversWorkflowEnded || (workflowEndedInvoker == null) || !workflowEndedInvoker
        .workflowEndedHandlerExists(workflowModuleId, bpmnProcessId)) {
      return;
    }
    log
        .warn(
            """
                A @WorkflowEnded method serves BPMN process '{}' of workflow module '{}', but the \
                Camunda 7 adapter '{}' did not attach its end listener - the method will never be \
                called although this engine could report the end of a workflow. This is a wiring \
                defect of the adapter, not of your application: please report it naming the \
                platform you run on (Spring Boot or Quarkus) and this adapter's version.""",
            bpmnProcessId,
            workflowModuleId,
            adapterId);

  }

  /**
   * Reports the start events the engine fires on its own (timer, signal,
   * conditional) to the core, which validates the application's
   * <code>&#64;WorkflowStartedByBpms</code> methods against them, and remembers the
   * PLAIN signal names for the listener attached at parse time.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine will know
   * @param model The BPMN model
   */
  private void wireBpmsInitiatedStarts(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    if (bpmsInitiatedStartInvoker == null) {
      return;
    }

    final var startEvents = startEventsOf(workflowModuleId, scopedBpmnProcessId, model);
    registerSignalStartEventsOf(workflowModuleId, scopedBpmnProcessId, startEvents);

    // throwing here honors the deployment-failure policy, like the task wiring
    bpmsInitiatedStartInvoker.validateBpmsInitiatedStarts(workflowModuleId, bpmnProcessId, startEvents);

    if (!startEvents.isEmpty()) {
      log
          .info(
              "Camunda7[{}]: BPMN process '{}' (workflow module '{}') is started by the BPMS itself: {}",
              adapterId,
              bpmnProcessId,
              workflowModuleId,
              startEvents);
    }

  }

  /**
   * The start events of one BPMN process which the engine fires on its own - a timer, a
   * signal or a condition - read from a model, whether this boot brings it or the engine
   * holds it.
   * <p>
   * One walk for both directions: the core validates the
   * <code>&#64;WorkflowStartedByBpms</code> methods of a deployed process against it, and
   * asks the same of a version the engine holds under an id nothing was deployed under, so
   * the two cannot disagree about what a start event is. A signal name is reported PLAIN,
   * because name-clash avoidance is nothing the application above this boundary knows
   * about.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param model The BPMN model
   * @return The start events, in the order the model lists them
   */
  private List<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOf(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    final var startEvents = new LinkedList<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec>();
    model
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent.class)
        .stream()
        .filter(startEvent -> scopedBpmnProcessId.equals(owningProcessId(startEvent)))
        .forEach(startEvent -> {
          final var definitions = startEvent.getEventDefinitions();
          definitions
              .stream()
              .filter(org.camunda.bpm.model.bpmn.instance.TimerEventDefinition.class::isInstance)
              .findFirst()
              .ifPresent(definition -> startEvents
                  .add(
                      new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec(
                          startEvent.getId(), io.vanillabp.spi.service.BpmsStartTrigger.Kind.TIMER, null, "timer")));
          definitions
              .stream()
              .filter(org.camunda.bpm.model.bpmn.instance.SignalEventDefinition.class::isInstance)
              .map(org.camunda.bpm.model.bpmn.instance.SignalEventDefinition.class::cast)
              .findFirst()
              .ifPresent(definition -> {
                // the model carries the SCOPED signal name where identifiers are
                // prefixed - the application is told the plain one
                final var scopedSignalName = definition.getSignal() == null
                    ? null
                    : definition.getSignal().getName();
                startEvents
                    .add(
                        new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec(
                            startEvent.getId(), io.vanillabp.spi.service.BpmsStartTrigger.Kind.SIGNAL, plainIdentifier(
                                workflowModuleId, scopedSignalName), "signal"));
              });
          definitions
              .stream()
              .filter(org.camunda.bpm.model.bpmn.instance.ConditionalEventDefinition.class::isInstance)
              .findFirst()
              .ifPresent(definition -> startEvents
                  .add(
                      new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec(
                          startEvent
                              .getId(), io.vanillabp.spi.service.BpmsStartTrigger.Kind.CONDITIONAL, null, "conditional")));
        });
    return startEvents;

  }

  /**
   * The start events of a model the engine still holds, read for the core's judgement of
   * the <code>&#64;WorkflowStartedByBpms</code> methods kept for a BPMN process id the
   * application declares without deploying anything under it.
   * <p>
   * Nothing wires such an id while this application boots, so those methods are judged by
   * nothing unless the old models are read - while the engine keeps firing the old
   * version's timer and keeps matching its signal subscription.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the engine assigned
   * @param model The model of that version
   * @return The start events of that version
   */
  private java.util.Collection<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOfHeldModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    return startEventsOf(workflowModuleId, scopedProcessId(workflowModuleId, bpmnProcessId), model);

  }

  /**
   * Remembers the PLAIN signal names of the signal start events of a model, which is what
   * the listener attached at parse time asks for once such a start fires.
   * <p>
   * A model the engine holds needs it as much as one this boot deploys: nothing was
   * deployed under a declared id, so nobody registered its signals, and a workflow the
   * engine starts under that id has to be told which signal fired.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param startEvents What {@link #startEventsOf} read from that model
   */
  private void registerSignalStartEventsOf(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final List<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEvents) {

    startEvents
        .stream()
        .filter(startEvent -> startEvent.kind() == io.vanillabp.spi.service.BpmsStartTrigger.Kind.SIGNAL)
        .forEach(startEvent -> taskRegistry
            .registerSignalStartEvent(
                workflowModuleId, scopedBpmnProcessId, startEvent.elementId(), startEvent.signalName()));

  }

  /**
   * Removes the workflow module's prefix from an identifier the model carries, so
   * the application sees what it modelled. Without scoping, or without a
   * prefix, the identifier is returned unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the model carries it
   * @return The plain identifier
   */
  private String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier) {

    if ((scoping == null) || (scopedIdentifier == null)) {
      return scopedIdentifier;
    }
    return scoping.plainIdentifier(workflowModuleId, scopedIdentifier, adapterId);

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

    // one deployment per workflow module; tenant id = workflow module id isolates BPMN
    // process ids between modules; duplicate filtering avoids redeploying unchanged models
    // Whether the module is isolated by a tenant is the mode's decision
    validateTenantConfiguration();
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      Camunda7TenantCheck.warnAboutUnregisteredTenant(adapterId, tenantId, identityService);
    }
    if (scoping != null) {
      scoping.validateNoCollidingProcessIds(
          adapterId,
          bpmsProcessingContext
              .getDeployedProcessIds()
              .stream()
              .map(processId -> new io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess(
                  workflowModuleId, processId))
              .toList());
    }
    var deploymentBuilder = repositoryService
        .createDeployment()
        .name(workflowModuleId)
        .source(ADAPTER_TYPE
            + ":"
            + adapterId)
        .enableDuplicateFiltering(true);
    if (tenantId != null) {
      deploymentBuilder = deploymentBuilder.tenantId(tenantId);
    }

    bpmsProcessingContext
        .getResourcesByFilename()
        .forEach(deploymentBuilder::addModelInstance);
    // the module's decision tables ride the SAME deployment: a business rule task
    // binding its decision to the deployment finds it, and both are versioned together
    final var builderWithProcesses = deploymentBuilder;
    bpmsProcessingContext
        .getDecisionsByFilename()
        .forEach((
            filename,
            dmn) -> builderWithProcesses
                .addInputStream(filename, new java.io.ByteArrayInputStream(dmn)));

    // deployWithResult reports the definitions the engine created, i.e. the version
    // it assigned to every model deployed now - they feed the version catalog, so the
    // version deployed by THIS boot needs no query at all
    final var deployment = deploymentBuilder.deployWithResult();
    final var deployedDefinitions = deployment.getDeployedProcessDefinitions();
    if (deployedDefinitions != null) {
      deployedDefinitions
          .forEach(definition -> {
            final var plainBpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, definition.getKey());
            processVersions
                .recordDeployed(
                    workflowModuleId,
                    plainBpmnProcessId,
                    definition.getId(),
                    definition.getVersion(),
                    definition.getVersionTag());
            // The border between the model this boot brought and the older
            // versions the engine still holds
            workflowTaskWiring
                .registerDeployedVersion(
                    adapterId,
                    workflowModuleId,
                    plainBpmnProcessId,
                    String.valueOf(definition.getVersion()));
            // and whether the engine holds workflows of it where this
            // configuration will never look
            Camunda7TenantCheck
                .warnAboutWorkflowsOutOfScope(
                    adapterId,
                    workflowModuleId,
                    plainBpmnProcessId,
                    definition.getKey(),
                    tenantId,
                    runtimeService);
          });
    }

    // Camunda deploys nothing when the resources did not change, so a restart without
    // a model change reports no definitions at all. The version this application runs
    // on is the engine's latest one then, and the old-versions check needs it on EVERY boot,
    // not only on the one which changed something.
    bpmsProcessingContext
        .getDeployedProcessIds()
        .stream()
        .filter(bpmnProcessId -> processVersions.deployedVersionOf(workflowModuleId, bpmnProcessId) == null)
        .forEach(bpmnProcessId -> {
          final var latest = latestVersionOf(workflowModuleId, bpmnProcessId);
          if (latest != null) {
            processVersions
                .recordDeployed(
                    workflowModuleId,
                    bpmnProcessId,
                    latest.getId(),
                    latest.getVersion(),
                    latest.getVersionTag());
            workflowTaskWiring
                .registerDeployedVersion(
                    adapterId, workflowModuleId, bpmnProcessId, String.valueOf(latest.getVersion()));
          }
        });

    final var deployedDecisions = deployment.getDeployedDecisionDefinitions();
    log.info(
        "Camunda7[{}]: deployed {} BPMN resource(s) and {} decision(s) of workflow module '{}' "
            + "(tenant '{}') as deployment '{}'",
        adapterId,
        bpmsProcessingContext.getResourcesByFilename().size(),
        bpmsProcessingContext.getDecisionsByFilename().size(),
        workflowModuleId,
        tenantId != null
            ? tenantId
            : "<none>",
        deployment.getId());
    if ((deployedDecisions != null) && !deployedDecisions.isEmpty()) {
      // the ids the ENGINE knows, which is what a business rule task has to name
      log.info(
          "Camunda7[{}]: the decisions of workflow module '{}' are known to the engine as {}",
          adapterId,
          workflowModuleId,
          deployedDecisions
              .stream()
              .map(decision -> "%s (version %d)".formatted(decision.getKey(), decision.getVersion()))
              .toList());
    }

    // The deployment is done, so the version tags the application's
    // annotations name can be resolved against what the engine has now

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // the workflows of a renamed BPMN process are served by the models the engine still
    // holds under the old id - before the executor may hand any of their tasks out
    wireTheProcessesNobodyDeployed(workflowModuleId);

    // asynchronous continuations (async-before/after, timers) run on the engine's
    // job executor - its activation is deferred to this point (the platform builds
    // the engine with the executor inactive)
    log.info(
        "Camunda7[{}]: starting workflow processing of module '{}'",
        adapterId,
        workflowModuleId);
    workflowProcessingLifecycle.startWorkflowProcessing(workflowModuleId);

  }

  /**
   * Wires the tasks of the models the engine still holds under a BPMN process id the
   * application DECLARES without deploying anything under it - the old id of a renamed
   * process, and the workflows which still run on it.
   * <p>
   * Camunda 7 evaluates the expressions of the model a workflow was STARTED with, so what
   * such a workflow needs is not a subscription but the connectables of ITS model: the
   * expression text of every task, keyed by the process id the engine reports and by the
   * element the expression is evaluated at. That model is right here, in the engine's own
   * repository, and reading it is what tells the difference between a task which completes
   * when its expression returns and one which stays open - a difference nothing outside a
   * model can be asked about, which is why what the core names as served is not enough on its
   * own here: it says what to compose an identifier from, and a connectable is more than an
   * identifier.
   * <p>
   * Every version the engine holds is wired, because a workflow may sit on any of them, and
   * a task which two versions share is registered once. It is also where the id gets the
   * warning about a <code>&#64;WorkflowEnded</code> method this engine cannot serve, since
   * no model of this boot passes by such an id. The wiring validation is NOT run
   * over those models: they were deployed by an earlier generation of this application, a
   * task the application dropped in the meantime is the core's startup check to report, and
   * ending the boot over a model nobody can change any more would be the wrong answer to it.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   */
  private void wireTheProcessesNobodyDeployed(
      final String workflowModuleId) {

    workflowTaskWiring
        .taskWiringOfProcessesNobodyDeployed(workflowModuleId)
        .keySet()
        .forEach(bpmnProcessId -> wireTheVersionsHeldUnder(workflowModuleId, bpmnProcessId));

  }

  /**
   * Wires every version the engine holds under one declared BPMN process id and says what
   * came of it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID nothing was deployed under
   */
  private void wireTheVersionsHeldUnder(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var definitionIdsByVersion = processVersions.definitionIdsHeldUnder(workflowModuleId, bpmnProcessId);
    if (definitionIdsByVersion.isEmpty()) {
      // either the last workflow of the old id ended and the engine forgot the
      // definitions, or the declared id is misspelled - the core's check says which,
      // naming the ids this module deploys
      log.debug(
          "Camunda7[{}]: the engine holds no process definition under the declared BPMN process '{}' "
              + "of workflow module '{}', so there is nothing of it to wire",
          adapterId,
          bpmnProcessId,
          workflowModuleId);
      return;
    }
    // the way back from the engine's definition key has to exist BEFORE any of those
    // models is read: reading one makes the engine PARSE the definition, and the parse
    // listener decides by exactly this registration whether the end of such a workflow
    // is reported (Camunda7AsyncBpmnParseListener#parseProcess). It also serves a
    // process without any task: the version of an execution and the workflow module it
    // belongs to are read from here
    taskRegistry.registerProcess(workflowModuleId, bpmnProcessId, scopedBpmnProcessId);

    // the workflows of those versions end like any other, so the application is told here
    // as well when this engine cannot deliver the end to a @WorkflowEnded method it kept
    // for the old id - no model of this boot passes by such an id, so nothing else says it
    warnAboutUnservedWorkflowEndedHandlers(workflowModuleId, bpmnProcessId);

    final var distinctConnectables = new java.util.LinkedHashMap<String, Camunda7TaskConnectable>();
    definitionIdsByVersion
        .forEach((
            version,
            definitionId) -> wireTheModelOf(
                workflowModuleId,
                bpmnProcessId,
                scopedBpmnProcessId,
                version,
                definitionId,
                distinctConnectables));
    distinctConnectables.values().forEach(taskRegistry::register);
    log.info(
        "Camunda7[{}]: wired {} task(s) of the {} version(s) the engine holds under the declared BPMN "
            + "process '{}' (workflow module '{}'), so the workflows still running on them keep being "
            + "served",
        adapterId,
        distinctConnectables.size(),
        definitionIdsByVersion.size(),
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // graceful shutdown (reverse start order): the executor stops once the LAST
    // started module stops (see Camunda7WorkflowProcessingLifecycle)
    log.info(
        "Camunda7[{}]: stopping workflow processing of module '{}'",
        adapterId,
        workflowModuleId);
    workflowProcessingLifecycle.stopWorkflowProcessing(workflowModuleId);

  }

}
