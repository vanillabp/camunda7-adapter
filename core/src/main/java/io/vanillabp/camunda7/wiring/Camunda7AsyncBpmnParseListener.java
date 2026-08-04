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
 */
public class Camunda7AsyncBpmnParseListener extends AbstractBpmnParseListener {

  /**
   * Delivers CANCELED lifecycle events to subscribing handlers - attached as an END
   * execution listener to service-like activities (see
   * {@link Camunda7TaskCancellationListener}).
   */
  private final Camunda7TaskCancellationListener cancellationListener;

  public Camunda7AsyncBpmnParseListener(
      final Camunda7TaskCancellationListener cancellationListener) {

    this.cancellationListener = cancellationListener;

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

  }

  @Override
  public void parseReceiveTask(
      final Element receiveTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    asyncAfterOnly(activity);

  }

}
