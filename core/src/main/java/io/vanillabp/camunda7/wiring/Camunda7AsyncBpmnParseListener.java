package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * Aligns the embedded engine's transaction boundaries with remote BPMS (Version-1
 * behavior): service-like tasks get <code>asyncBefore</code> AND
 * <code>asyncAfter</code> - every task runs in its own job transaction, so the
 * transaction ends when the task completes. Running several service tasks within
 * one transaction is an anti-pattern (its scope would be engine-specific and
 * surprising, and a late failure would roll back completed tasks). Wait-state
 * tasks (user/receive tasks) only get <code>asyncAfter</code>. Applied at parse
 * time - the deployed BPMN XML stays untouched, the parsed process definition
 * carries the flags.
 * <p>
 * Why this adapter edits the model it deploys at all, and what bounds each edit, is decision 5 in
 * the repository's DECISIONS.md.
 */
public class Camunda7AsyncBpmnParseListener extends AbstractBpmnParseListener {

  /**
   * Delivers CANCELED lifecycle events to subscribing handlers - attached as an END
   * execution listener to service-like activities (see
   * {@link Camunda7TaskCancellationListener}).
   */
  private final Camunda7TaskCancellationListener cancellationListener;

  /**
   * Notifies optional <code>&#64;WorkflowTask</code> handlers about user-task
   * lifecycle events - attached to user tasks for the engine's global
   * CREATE and DELETE task-listener events.
   */
  private final Camunda7UserTaskEventListener userTaskEventListener;

  /**
   * Builds the workflow aggregate of a workflow the engine started on its own -
   * attached to timer, signal and conditional start events. May be
   * <code>null</code>: an engine built without it simply does not serve such
   * processes.
   */
  private final java.util.function.Function<io.vanillabp.spi.service.BpmsStartTrigger.Kind, org.camunda.bpm.engine.delegate.ExecutionListener> bpmsInitiatedStartListenerFactory;

  /**
   * Tells the application that a workflow ended - attached to the PROCESS
   * scope, but only where a <code>&#64;WorkflowEnded</code> method exists. May be
   * <code>null</code>: an engine built without it never notifies.
   */
  private io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener workflowEndedListener;

  /**
   * Decides whether a process definition needs the end listener - the pair of
   * (tenant, process definition key) is what the engine reports at parse time.
   */
  private java.util.function.BiPredicate<String, String> workflowEndedHandlerExists;

  /**
   * Hands over the end-of-workflow notification.
   *
   * @param workflowEndedListener The listener to attach
   * @param workflowEndedHandlerExists Whether a process definition needs it
   */
  public void setWorkflowEnded(
      final io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener workflowEndedListener,
      final java.util.function.BiPredicate<String, String> workflowEndedHandlerExists) {

    this.workflowEndedListener = workflowEndedListener;
    this.workflowEndedHandlerExists = workflowEndedHandlerExists;

  }

  @Override
  public void parseProcess(
      final Element processElement,
      final org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity processDefinition) {

    if ((workflowEndedListener == null) || (workflowEndedHandlerExists == null)) {
      return;
    }
    // a model must not pay for a notification the application did not ask for
    if (!workflowEndedHandlerExists.test(processDefinition.getTenantId(), processDefinition.getKey())) {
      return;
    }
    // 'addListener' and not the deprecated 'addExecutionListener', which does nothing but
    // delegate here, and not 'addBuiltInListener' either: a built-in listener is the
    // ENGINE's own and is the only kind which still runs when a caller skips custom
    // listeners (AbstractEventAtomicOperation asks getBuiltInListeners then). Moving the
    // notification there would make it fire on a forced deletion as well, which is a
    // behaviour change, and the cancellation and start listeners below register the same
    // way
    processDefinition
        .addListener(
            org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END,
            workflowEndedListener);

  }

  public Camunda7AsyncBpmnParseListener(
      final Camunda7TaskCancellationListener cancellationListener,
      final Camunda7UserTaskEventListener userTaskEventListener) {

    this(cancellationListener, userTaskEventListener, null);

  }

  public Camunda7AsyncBpmnParseListener(
      final Camunda7TaskCancellationListener cancellationListener,
      final Camunda7UserTaskEventListener userTaskEventListener,
      final java.util.function.Function<io.vanillabp.spi.service.BpmsStartTrigger.Kind, org.camunda.bpm.engine.delegate.ExecutionListener> bpmsInitiatedStartListenerFactory) {

    this.cancellationListener = cancellationListener;
    this.userTaskEventListener = userTaskEventListener;
    this.bpmsInitiatedStartListenerFactory = bpmsInitiatedStartListenerFactory;

  }

  @Override
  public void parseStartEvent(
      final Element startEventElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (bpmsInitiatedStartListenerFactory == null) {
      return;
    }
    // only the start events the ENGINE fires on its own need an aggregate built for
    // them; a none start event is the application's business, a message start event
    // arrives through ProcessService#startWorkflowByMessage carrying its aggregate
    final var kind = Camunda7StartEvents.kindOf(startEventElement);
    if (kind == null) {
      return;
    }
    activity
        .addListener(
            org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_START,
            bpmsInitiatedStartListenerFactory.apply(kind));

  }

  private void asyncBeforeAndAfter(
      final ActivityImpl activity) {

    activity.setAsyncBefore(true, true);
    activity.setAsyncAfter(true, true);
    activity.addListener(
        org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END,
        cancellationListener);

  }

  private void asyncAfterOnly(
      final ActivityImpl activity) {

    activity.setAsyncBefore(false);
    activity.setAsyncAfter(true, true);

  }

  @Override
  public void parseServiceTask(
      final Element serviceTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncBeforeAndAfter(activity);

  }

  @Override
  public void parseSendTask(
      final Element sendTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncBeforeAndAfter(activity);

  }

  @Override
  public void parseBusinessRuleTask(
      final Element businessRuleTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncBeforeAndAfter(activity);

  }

  @Override
  public void parseScriptTask(
      final Element scriptTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncBeforeAndAfter(activity);

  }

  @Override
  public void parseUserTask(
      final Element userTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncAfterOnly(activity);
    // user-task lifecycle notifications: the engine's global CREATE
    // and DELETE task-listener events reach optional @WorkflowTask handlers -
    // BUILT-IN listeners run before any modeller-defined ones (V1 semantics)
    final var taskDefinition = ((org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior) activity
        .getActivityBehavior()).getTaskDefinition();
    taskDefinition.addBuiltInTaskListener(
        org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_CREATE,
        userTaskEventListener);
    taskDefinition.addBuiltInTaskListener(
        org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_DELETE,
        userTaskEventListener);

  }

  @Override
  public void parseReceiveTask(
      final Element receiveTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncAfterOnly(activity);

  }

}
