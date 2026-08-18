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
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterPlatformVersion;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
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
 * dispatches task executions through the core's {@code WorkflowTaskInvoker}.
 */
@Slf4j
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
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * The task connectables of this adapter id's engine, registered during
   * {@link #wireBpmn} and looked up by the engine's EL resolver.
   */
  private final Camunda7TaskRegistry taskRegistry;

  /**
   * The core's entry point for workflows the engine starts on its own (story 41):
   * the start events of a process are reported here while wiring. May be
   * <code>null</code> (tests) - nothing is reported then.
   */
  private io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * Hands over the core's entry point for workflows the engine starts on its own.
   *
   * @param bpmsInitiatedStartInvoker The core's invoker
   */
  public void setBpmsInitiatedStartInvoker(
      final io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker) {

    this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;

  }

  /**
   * The core's registry of <code>&#64;WorkflowEnded</code> methods, used at deployment
   * to tell an application that its method will never be called. May be
   * <code>null</code> (tests) - nothing is checked then.
   */
  private io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * Whether the engine of this adapter id attached the end listener, see
   * {@link #setWorkflowEndedSupport}.
   */
  private boolean engineDeliversWorkflowEnded;

  /**
   * Hands over what is needed to check <code>&#64;WorkflowEnded</code> methods against
   * what this adapter id's engine actually delivers.
   *
   * @param workflowEndedInvoker The core's registry of end handlers
   * @param engineDeliversWorkflowEnded Whether the engine attached its end listener
   */
  public void setWorkflowEndedSupport(
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker,
      final boolean engineDeliversWorkflowEnded) {

    this.workflowEndedInvoker = workflowEndedInvoker;
    this.engineDeliversWorkflowEnded = engineDeliversWorkflowEnded;

  }

  /**
   * The core's name-clash-avoidance model (story 35): decides whether a workflow
   * module is isolated by the Camunda TENANT ({@code by-adapter}, version 1's
   * behavior), by PREFIXING the identifiers ({@code use-prefix} - no tenant) or not at
   * all ({@code none}, this adapter's default). May be <code>null</code> (tests).
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

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
   * Sets the name-clash-avoidance support (the platform modules construct this
   * service and inject it afterwards).
   *
   * @param scoping The name-clash-avoidance support
   */
  public void setScoping(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this.scoping = scoping;

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
   * {@code by-adapter}, none under {@code use-prefix}/{@code none} (story 35).
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
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this(adapterId, repositoryService, workflowProcessingLifecycle, workflowTaskInvoker, taskRegistry, null);

  }

  public Camunda7DeploymentService(
      final String adapterId,
      final RepositoryService repositoryService,
      final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Camunda7TaskRegistry taskRegistry,
      final java.util.function.Function<String, Camunda7InstanceIdentity> instanceIdentities) {

    AdapterPlatformVersion.requireCompatiblePlatform(ADAPTER_TYPE, Camunda7DeploymentService.class);

    this.adapterId = adapterId;
    this.repositoryService = repositoryService;
    this.workflowProcessingLifecycle = workflowProcessingLifecycle;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskRegistry = taskRegistry;
    this.instanceIdentities = instanceIdentities;
    // story 48: what the engine's process definitions are versioned as - the
    // registry hands it to every listener building an invocation context
    this.processVersions = new io.vanillabp.camunda7.wiring.Camunda7ProcessVersions(
        repositoryService, this::scopedProcessId, this::tenantIdOf, this::tasksOfDeployedModel);
    if (taskRegistry != null) {
      taskRegistry.setProcessVersions(processVersions);
      // every inbound delivery reports which adapter it came from (story 54)
      taskRegistry.setAdapterId(adapterId);
    }

  }

  /**
   * The versions of this engine's process definitions (story 48): the source of the
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
   * Camunda 7 defaults to {@code none} although its engine is multi-tenant: it keeps
   * workflow modules apart in more than one way (a tenant, prefixed identifiers, or an
   * engine of its own per module), and which one an application wants is not something
   * to presume. The choice is asked for by
   * {@link #warnAboutUnscopedIdentifiers(String, boolean)} instead.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.NameClashAvoidance defaultNameClashAvoidance() {

    return io.vanillabp.integration.adapter.spi.NameClashAvoidance.NONE;

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
    // story 35: rewrite the identifiers the engine resolves across process
    // definitions BEFORE wiring - a no-op unless the mode is 'use-prefix'. The core
    // calls prepareBpmn once per executable PROCESS while all processes of a file
    // share ONE model, so scoping has to happen once per FILE - otherwise a
    // multi-process file would collect one prefix per process.
    final var modelAlreadyScoped = context
        .getResourcesByFilename()
        .containsKey(filename);
    if (!modelAlreadyScoped) {
      // story 61: a call activity of this engine does not pass the business key -
      // which holds the workflow aggregate's ID - unless the model says so. Injected
      // BEFORE scoping, which rewrites the called elements: here the process IDs are
      // still the ones the application knows
      io.vanillabp.camunda7.wiring.Camunda7CallActivities
          .propagateBusinessKey(model, workflowModuleId, workflowTaskInvoker);
      io.vanillabp.camunda7.wiring.Camunda7Scoping.apply(model, workflowModuleId, adapterId, scoping);
    }
    context.addResource(filename, model);
    context.recordDeployedProcess(bpmnProcessId);
    return context;

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
    // by the scoped id and the invoker is called with the plain one (story 35)
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    collectTasks(model, workflowModuleId, bpmnProcessId, scopedBpmnProcessId, filename, specs, connectables);

    // both directions with guiding messages; throwing here honors the
    // deployment-failure policy for non-first-priority adapter ids
    workflowTaskInvoker.validateTaskWiring(workflowModuleId, bpmnProcessId, specs);

    // story 50: a task wired by 'camunda:expression' completes as soon as the
    // expression returns, so a method declaring @TaskId can never keep it open.
    // The engine's EL resolver says the same at runtime, but only once a workflow
    // reaches the task - asking the core here moves the verdict to the boot. The
    // reverse case needs no message: 'camunda:delegateExpression' serves a method
    // without @TaskId just as well, the behavior leaves the activity when the
    // handler returns.
    connectables
        .stream()
        .filter(connectable -> connectable.type() == Camunda7TaskConnectable.Type.EXPRESSION)
        .filter(connectable -> workflowTaskInvoker.workflowTaskCompletesAsynchronously(
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

    // story 48: the engine can be asked which versions of this process it has, which
    // is what a version specification naming a version TAG needs
    workflowTaskInvoker
        .registerProcessVersions(adapterId, workflowModuleId, bpmnProcessId, processVersions);

    // story 59: which elements can put a second token into a running workflow - two
    // tokens are two writers on the workflow aggregate, and the core knows whether
    // that aggregate can survive them
    workflowTaskInvoker
        .reportConcurrentTokenElements(
            workflowModuleId,
            bpmnProcessId,
            Camunda7ConcurrentTokens.elementIdsOf(model, scopedBpmnProcessId));

    // story 66: an expression reading an attribute the aggregate does not share
    // evaluates to null, and Camunda 7 then takes the default flow without saying a
    // word. The adapter knows the model, the core knows what is shared - together they
    // can say it while the application starts
    warnAboutUnsharedAggregateProperties(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);

    // story 72: this engine reports the end of a workflow, so a @WorkflowEnded
    // method staying silent means the adapter was not wired - which used to be
    // invisible: the application booted, the workflow ran, the method was never
    // called and nothing was logged
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
   * The engine's runtime service, used by the startup check of story 57 to ask how
   * many workflows still run on an old version of a process. Set by the platform
   * integration, like the identity service.
   *
   * @param runtimeService The engine's runtime service
   */
  public void setRuntimeService(
      final org.camunda.bpm.engine.RuntimeService runtimeService) {

    processVersions.setRuntimeService(runtimeService);

  }

  /**
   * The process definition the engine considers current for that process - what this
   * application runs on when its resources were deployed before (story 57).
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
   * The tasks of a model the engine still holds, read for the startup check of story
   * 57 - the same extraction the deployed model goes through, so both directions
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
   * Extracts the tasks of ONE executable BPMN process into the specs the core
   * validates against, and - for the model this boot deploys - into the connectables
   * the engine's EL resolver looks up at runtime.
   * <p>
   * Story 57 reads the models of OLDER versions the engine still holds and asks the
   * core whether the application still serves them, which is why this sits in its own
   * method: both directions have to see a model exactly the same way, and a second
   * implementation would drift.
   *
   * @param model The BPMN model, carrying the identifiers the ENGINE knows
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The BPMN process ID as the engine knows it
   * @param describedSource What to name in a message about a broken model (the file
   *          for the deployed model, the version for an older one)
   * @param specs Collects the task specs
   * @param connectables Collects the connectables, or <code>null</code> for a model
   *          which is not being deployed
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
            throw new IllegalStateException(
                """
                    Task '%s' of BPMN process '%s' (file '%s', workflow module '%s') is implemented \
                    as an external task (camunda:topic) which is not supported by VanillaBP yet! \
                    Wire the task by 'camunda:expression' or 'camunda:delegateExpression' naming the \
                    @WorkflowTask method's task definition, e.g. ${%s}."""
                    .formatted(task.getId(), bpmnProcessId, describedSource, workflowModuleId, topic));
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
          final var taskDefinition = unwrapExpression(
              rawExpression, task.getId(), bpmnProcessId, describedSource, workflowModuleId);
          specs.add(new BpmnTaskSpec(task.getId(), taskDefinition));
          if (connectables != null) {
            connectables.add(new Camunda7TaskConnectable(
                workflowModuleId, bpmnProcessId, scopedBpmnProcessId, task.getId(), taskDefinition, type));
          }
        });

    // user tasks (story 24): the task definition is the camunda:formKey; a
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
      final String filename,
      final String workflowModuleId) {

    final var matcher = EL_PATTERN.matcher(rawExpression.trim());
    if (!matcher.matches()) {
      throw new IllegalStateException(
          """
              The expression '%s' of task '%s' of BPMN process '%s' (file '%s', workflow module \
              '%s') is not supported by VanillaBP! Use a simple expression naming the @WorkflowTask \
              method's task definition, e.g. ${myTaskDefinition}."""
              .formatted(rawExpression, elementId, bpmnProcessId, filename, workflowModuleId));
    }
    return matcher.group(1).trim();

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
  /**
   * Reports every expression of the model which reads an attribute of the workflow
   * aggregate that is NOT shared with the BPMS (story 66).
   * <p>
   * A WARN, not a failed deployment: the check reads expressions, and an expression it
   * misreads must not keep an application from starting. What it finds is precise enough
   * to act on - the element, the expression, the attribute and the annotation which fixes
   * it - and a model which works produces nothing at all.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param scopedBpmnProcessId The process ID as the engine knows it
   * @param model The deployed model
   */
  private void warnAboutUnsharedAggregateProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    final var identifiers = io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers
        .of(model, scopedBpmnProcessId);
    if (identifiers.isEmpty()) {
      return;
    }
    workflowTaskInvoker
        .unsharedWorkflowAggregateProperties(
            workflowModuleId,
            bpmnProcessId,
            identifiers.keySet(),
            io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE)
        .forEach(name -> {
          final var origin = identifiers.get(name);
          log.warn(
              """
                  Camunda7[{}]: the expression '{}' of element '{}' (BPMN process '{}' of workflow \
                  module '{}') reads '{}', which IS an attribute of the workflow aggregate but is \
                  NOT shared with the BPMS - the engine evaluates it as null, so a condition \
                  reading it takes the default flow without any error. Three ways out, pick the \
                  one which applies: share the attribute (@SyncWithBPMS on its getter); give it a \
                  readable getter if it has none, because the shared values are read from getX() \
                  and from isX() returning boolean, never from a field and never from an isX() \
                  returning something else (VanillaBP 1 read those, this version does not); or let \
                  the expression read something the aggregate does share. Until you do, VanillaBP \
                  2.0 still answers this expression by reading the aggregate directly - version \
                  2.1 removes that fallback.""",
              adapterId,
              origin.expression(),
              origin.elementId(),
              bpmnProcessId,
              workflowModuleId,
              name);
        });

  }

  /**
   * Reports a <code>&#64;WorkflowEnded</code> method which this adapter id will never
   * call. Camunda 7 CAN report the end of a workflow, so the only way to get here is a
   * missing wire between the platform module and the engine - it happened
   * (story 72: the Quarkus producer did not hand the invoker over, and nothing said
   * so). The deployment is not failed over it: the workflow itself runs, only the
   * notification is missing.
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

  private void wireBpmsInitiatedStarts(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    if (bpmsInitiatedStartInvoker == null) {
      return;
    }

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
                // prefixed (story 35) - the application is told the plain one
                final var scopedSignalName = definition.getSignal() == null
                    ? null
                    : definition.getSignal().getName();
                final var signalName = plainIdentifier(workflowModuleId, scopedSignalName);
                startEvents
                    .add(
                        new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec(
                            startEvent
                                .getId(), io.vanillabp.spi.service.BpmsStartTrigger.Kind.SIGNAL, signalName, "signal"));
                taskRegistry
                    .registerSignalStartEvent(
                        workflowModuleId, scopedBpmnProcessId, startEvent.getId(), signalName);
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
   * Removes the workflow module's prefix from an identifier the model carries, so
   * the application sees what it modelled (story 35). Without scoping, or without a
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
    // story 35: whether the module is isolated by a tenant is the mode's decision
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

    // deployWithResult reports the definitions the engine created, i.e. the version
    // it assigned to every model deployed now - story 48 feeds them into the version
    // catalog, so the version deployed by THIS boot needs no query at all
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
            // story 57: the border between the model this boot brought and the older
            // versions the engine still holds
            workflowTaskInvoker
                .registerDeployedVersion(
                    adapterId,
                    workflowModuleId,
                    plainBpmnProcessId,
                    String.valueOf(definition.getVersion()));
          });
    }

    // Camunda deploys nothing when the resources did not change, so a restart without
    // a model change reports no definitions at all. The version this application runs
    // on is the engine's latest one then, and story 57's check needs it on EVERY boot,
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
            workflowTaskInvoker
                .registerDeployedVersion(
                    adapterId, workflowModuleId, bpmnProcessId, String.valueOf(latest.getVersion()));
          }
        });

    log.info(
        "Camunda7[{}]: deployed {} BPMN resource(s) of workflow module '{}' (tenant '{}') as deployment '{}'",
        adapterId,
        bpmsProcessingContext.getResourcesByFilename().size(),
        workflowModuleId,
        tenantId != null
            ? tenantId
            : "<none>",
        deployment.getId());

    // story 48: the deployment is done, so the version tags the application's
    // annotations name can be resolved against what the engine has now
    workflowTaskInvoker.resolveProcessVersions(workflowModuleId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // asynchronous continuations (async-before/after, timers) run on the engine's
    // job executor - its activation is deferred to this point (the platform builds
    // the engine with the executor inactive)
    log.info(
        "Camunda7[{}]: starting workflow processing of module '{}'",
        adapterId,
        workflowModuleId);
    workflowProcessingLifecycle.startWorkflowProcessing(workflowModuleId);

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
