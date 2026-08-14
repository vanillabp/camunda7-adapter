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
 * Resolves top-level EL names of BPMN expressions against VanillaBP (Version-1
 * approach, ported): a name matching a wired task (by the current BPMN element or
 * by task definition) yields the handler - <code>camunda:expression</code> tasks
 * run the handler during evaluation, <code>camunda:delegateExpression</code> tasks
 * receive the {@link Camunda7WorkflowTaskBehavior} (so <code>&#64;TaskId</code>
 * tasks can stay open). Any other name is resolved as an attribute of the workflow
 * aggregate identified by the execution's business key (getter, boolean getter or
 * field - e.g. gateway conditions like <code>${riskAcceptable}</code>); unresolved
 * names fall through to the engine's remaining EL resolvers (e.g. platform beans).
 */
public class Camunda7TaskELResolver extends ELResolver {

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Story 35: needed to translate a {@code TaskException}'s error code into what the
   * engine knows. Settable - the resolver is created by the engine configuration.
   */
  @lombok.Setter
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * The adapter id this resolver serves (story 35).
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

    // story 35: without a tenant (prefixed identifiers) the registry answers which
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
        // user-task connectables (story 24) are served by task listeners, never
        // by EL names - a formKey colliding with an aggregate attribute must not
        // shadow the attribute
        .filter(candidate -> candidate.type() != Camunda7TaskConnectable.Type.USER_TASK)
        // a connectable matched by ELEMENT catches EVERY name evaluated while an
        // execution sits at its activity, including the condition of a conditional
        // event reading the workflow aggregate. Where the aggregate has an attribute
        // of that name, the attribute is what the model means - the task is served by
        // its own name, or by the element while no attribute is in the way
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
        // the backstop: the deployment check (story 50) reports this while the
        // application starts, so reaching this line means the model got to the
        // engine another way
        throw new ProcessEngineException(
            Camunda7TaskConnectable.asynchronousTaskWiredByExpression(
                propertyName, bpmnProcessId, workflowModuleId));
      }
      return null;
    }

    // no wired task: resolve as workflow-aggregate attribute (business key =
    // serialized aggregate ID); unresolved names fall through
    final var businessKey = execution.getBusinessKey();
    if (businessKey == null) {
      return null;
    }
    final var value = workflowTaskInvoker.resolveWorkflowAggregateProperty(
        workflowModuleId,
        bpmnProcessId,
        businessKey,
        propertyName);
    if (value != null) {
      context.setPropertyResolved(true);
    }
    return value;

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
