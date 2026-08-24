package io.vanillabp.camunda7.wiring;

import java.beans.FeatureDescriptor;
import java.util.Iterator;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.impl.juel.jakarta.el.ELContext;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Resolves top-level EL names of BPMN expressions against the WIRED TASKS of VanillaBP: a
 * name matching a task (by the current BPMN element or by task definition) yields the
 * handler - <code>camunda:expression</code> tasks run the handler during evaluation,
 * <code>camunda:delegateExpression</code> tasks receive the
 * {@link Camunda7WorkflowTaskBehavior} (so <code>&#64;TaskId</code> tasks can stay open).
 * Every other name falls through to the engine's remaining resolvers, which is where the
 * process variables live.
 * <p>
 * <b>Attributes of the workflow aggregate are a MIGRATION FALLBACK here</b>, removed in
 * 2.1. Reading the aggregate live made a model reading <code>${riskAcceptable}</code> work
 * on Camunda 7 and fail on every remote BPMS - the opposite of what {@code @SyncWithBPMS}
 * is for. The values are pushed as process variables at every sync point now, so the engine
 * resolves them itself (see decision 1 in the repository's README.md).
 * <p>
 * The fallback exists because an application upgrading to this version has workflows
 * RUNNING which carry no such variables yet, and because version 1 resolved attributes
 * without a getter as well (the sync model reads getters only). So the order is reversed
 * compared to before: a VARIABLE of that name wins, and only where the engine has none
 * does the resolver still read the aggregate - saying so once per name, with the way out.
 * The startup check of the deployment service reports the same names while the application
 * boots.
 */
public class Camunda7TaskELResolver extends ELResolver {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Camunda7TaskELResolver.class);

  /**
   * Which live reads were reported already (workflow module, process and name) - the
   * migration fallback names a configuration gap, and one line per evaluation
   * would bury it.
   */
  private final java.util.Set<String> liveReadsReported = java.util.concurrent.ConcurrentHashMap
      .newKeySet();

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Needed to translate a {@code TaskException}'s error code into what the
   * engine knows. Settable - the resolver is created by the engine configuration.
   */
  @lombok.Setter
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * The adapter id this resolver serves.
   */
  @lombok.Setter
  private String adapterId;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  public Camunda7TaskELResolver(
      final Camunda7TaskRegistry taskRegistry,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this.taskRegistry = taskRegistry;
    this.workflowTaskInvoker = workflowTaskInvoker;

  }

  // both calls into the deprecated half of WorkflowTaskInvoker are the migration
  // fallback, and this resolver is the only production caller of it: the
  // 'removal' lint is mandatory and @Deprecated on a caller does not silence it, so
  // without this the adapter reported the same two warnings in every build. Goes away
  // in 2.1 together with the fallback.
  @SuppressWarnings("removal")
  @Override
  public Object getValue(
      final ELContext context,
      final Object base,
      final Object property) {

    // only top-level names; attribute chains are served by subsequent resolvers
    if (base != null) {
      return null;
    }

    final var execution = (ExecutionEntity) context
        .getELResolver()
        .getValue(context, null, "execution");
    // the built-in variable-scope resolver marks 'execution' as resolved - reset
    // so OUR verdict decides whether subsequent resolvers get their chance
    context.setPropertyResolved(false);
    if (execution == null) {
      return null;
    }

    // Without a tenant (prefixed identifiers) the registry answers which
    // workflow module a process definition key belongs to, and its plain id
    final var scopedBpmnProcessId = execution
        .getProcessDefinition()
        .getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(execution.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);
    final var currentElement = execution.getBpmnModelElementInstance();
    final var currentElementId = currentElement != null
        ? currentElement.getId()
        : null;
    final var propertyName = property.toString();

    final var connectable = taskRegistry
        .resolve(
            workflowModuleId,
            scopedBpmnProcessId,
            currentElementId,
            propertyName)
        // user-task connectables are served by task listeners, never
        // by EL names - a formKey colliding with an aggregate attribute must not
        // shadow the attribute
        .filter(candidate -> candidate.type() != Camunda7TaskConnectable.Type.USER_TASK)
        // a connectable matched by ELEMENT catches EVERY name evaluated while an
        // execution sits at its activity, including the condition of a conditional event
        // or a gateway. Only a name which IS a task definition of this process may run a
        // handler; anything else evaluated at that element is a variable and belongs to
        // the engine's resolvers
        .filter(
            candidate -> taskRegistry
                .isTaskDefinitionName(workflowModuleId, scopedBpmnProcessId, propertyName) || !workflowTaskInvoker
                    .workflowAggregateHasProperty(workflowModuleId, bpmnProcessId, propertyName));
    if (connectable.isPresent()) {
      context.setPropertyResolved(true);
      final var behavior = new Camunda7WorkflowTaskBehavior(
          connectable.get(), workflowTaskInvoker, scoping, adapterId, taskRegistry);
      if (connectable.get().type() == Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION) {
        // the engine treats the resolved object as the task's activity behavior
        return behavior;
      }
      // camunda:expression - the handler runs while the expression evaluates;
      // the task completes when the evaluation returns
      final var outcome = behavior.invokeHandler(execution);
      if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETION_PENDING) {
        // the backstop: the deployment check reports this while the
        // application starts, so reaching this line means the model got to the
        // engine another way
        throw new ProcessEngineException(
            Camunda7TaskConnectable.asynchronousTaskWiredByExpression(
                propertyName, bpmnProcessId, workflowModuleId));
      }
      return null;
    }

    // no wired task: the values shared by the aggregate are process variables, so
    // the engine's own resolvers answer the name - unless this workflow still
    // runs without them, which is what the migration fallback below is for
    if (execution.hasVariable(propertyName)) {
      return null;
    }
    final var businessKey = execution.getBusinessKey();
    if (businessKey == null) {
      return null;
    }
    final var value = workflowTaskInvoker.resolveWorkflowAggregateProperty(
        workflowModuleId,
        bpmnProcessId,
        businessKey,
        propertyName);
    if (value == null) {
      return null;
    }
    reportLiveRead(workflowModuleId, bpmnProcessId, propertyName);
    context.setPropertyResolved(true);
    return value;

  }

  /**
   * Says ONCE per workflow module, process and name that an expression was answered by
   * reading the aggregate instead of a process variable - the migration fallback which
   * version 2.1 removes.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param propertyName The attribute read
   */
  private void reportLiveRead(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String propertyName) {

    if (!liveReadsReported.add("%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, propertyName))) {
      return;
    }
    log.warn(
        """
            Camunda7[{}]: the expression '{}' of BPMN process '{}' (workflow module '{}') was \
            answered by reading the workflow aggregate directly, because the workflow carries no \
            process variable of that name. That is the MIGRATION FALLBACK of VanillaBP 2.0, and \
            version 2.1 REMOVES it - a workflow started with this version writes the variable at \
            every sync point. To become independent of it: make the attribute a readable getter \
            (the values shared with a BPMS are read from getters, never from fields) and make sure \
            it is shared (@SyncWithBPMS on the getter, or an aggregate class which shares \
            everything - the default). Workflows which were already running when you upgraded keep \
            working through this fallback until they end.""",
        adapterId,
        propertyName,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public Class<?> getType(
      final ELContext context,
      final Object base,
      final Object property) {

    return Object.class;

  }

  @Override
  public void setValue(
      final ELContext context,
      final Object base,
      final Object property,
      final Object value) {

    if ((base == null) && (getValue(context, null, property) != null)) {
      throw new ProcessEngineException(
          "Cannot set value of '%s' - it resolves to VanillaBP-managed state.".formatted(property));
    }
    context.setPropertyResolved(false);

  }

  @Override
  public boolean isReadOnly(
      final ELContext context,
      final Object base,
      final Object property) {

    return true;

  }

  @Override
  public Class<?> getCommonPropertyType(
      final ELContext context,
      final Object base) {

    return Object.class;

  }

  @Override
  public Iterator<FeatureDescriptor> getFeatureDescriptors(
      final ELContext context,
      final Object base) {

    return null;

  }

}
