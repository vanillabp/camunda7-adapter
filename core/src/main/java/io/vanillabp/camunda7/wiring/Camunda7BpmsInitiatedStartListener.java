package io.vanillabp.camunda7.wiring;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Attached to the start events the engine fires on its own (timer, signal,
 * conditional - see {@link Camunda7AsyncBpmnParseListener}), this listener gives the
 * new workflow its workflow aggregate: it asks the core to build one and stores the
 * aggregate's ID as the instance's BUSINESS KEY, which is how everything else in
 * this adapter finds a workflow again.
 * <p>
 * It runs inside the engine's own transaction (the timer job's, respectively the
 * command's), so aggregate and process instance commit together.
 * <p>
 * A business key the instance already carries does not end this listener's work, and
 * that is the point of it. On Camunda 7 the business key IS the workflow aggregate's id,
 * so a key names an aggregate rather than telling who started the workflow: anybody with
 * access to the engine can start one of these processes and choose the key. The only
 * reliable sign of a start the application made is that the id already has an aggregate,
 * which is the rule this listener applies. So the key is handed to the core as the name
 * the workflow already goes by, and what comes back says which case it was: an aggregate
 * which existed is the application's own start (or a workflow taken over from version 1,
 * which carries its id in the key and nowhere else), while one which was created belongs
 * to a workflow somebody started past VanillaBP.
 * <p>
 * The one case the adapter refuses is a key which cannot be an id of that aggregate at
 * all - a text where the id attribute is a number or a UUID. VanillaBP would then have to
 * give the workflow an id of its own and overwrite a name somebody else chose, so it
 * builds nothing and says why.
 * <p>
 * Two things this cannot see, both of them on purpose. A key somebody chose which looks
 * like the id of an existing aggregate attaches that workflow to it without a word, and
 * nothing on Camunda 7 can catch that, because catching it needs two values and there is
 * one. And an application which deletes its aggregate while the workflow still runs looks
 * like a foreign start at the next BPMS-initiated start of the same id. All of this is
 * decision 24 in the repository's DECISIONS.md.
 * <p>
 * Why a listener is added to the deployed model, and only where a handler exists, is decision 5 in
 * the repository's DECISIONS.md.
 */
public class Camunda7BpmsInitiatedStartListener implements ExecutionListener {

  private static final Logger log = LoggerFactory
      .getLogger(Camunda7BpmsInitiatedStartListener.class);

  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Which kind of start event this listener sits on, decided at parse time.
   */
  private final BpmsStartTrigger.Kind kind;

  public Camunda7BpmsInitiatedStartListener(
      final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final Camunda7TaskRegistry taskRegistry,
      final BpmsStartTrigger.Kind kind) {

    this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;
    this.taskRegistry = taskRegistry;
    this.kind = kind;

  }

  @Override
  public void notify(
      final DelegateExecution execution) {

    final var businessKey = (execution.getProcessBusinessKey() == null) || execution.getProcessBusinessKey().isBlank()
        ? null
        : execution.getProcessBusinessKey();

    final var processDefinitionKey = execution.getProcessEngineServices()
        .getRepositoryService()
        .getProcessDefinition(execution.getProcessDefinitionId())
        .getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(execution.getTenantId(), processDefinitionKey);
    if (workflowModuleId == null) {
      log
          .debug(
              "Camunda7: no workflow module known for process definition '{}' - the start of instance "
                  + "'{}' is not a VanillaBP workflow",
              processDefinitionKey,
              execution.getProcessInstanceId());
      return;
    }
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey);

    final var signalName = taskRegistry
        .signalNameOfStartEvent(workflowModuleId, processDefinitionKey, execution.getCurrentActivityId());
    final var processVersion = taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());
    final var result = bpmsInitiatedStartInvoker
        .startWorkflowByBpms(
            workflowModuleId,
            bpmnProcessId,
            contextOf(execution, signalName, processVersion, businessKey));

    if (businessKey == null) {
      // the aggregate's ID is this adapter's handle on the workflow (business key) -
      // set within the same transaction which created the instance
      ((PvmExecutionImpl) execution).setProcessBusinessKey(result.workflowAggregateId());

      log
          .debug(
              "Camunda7: the BPMS started '{}' of workflow module '{}' by start event '{}' - workflow "
                  + "aggregate '{}' {}",
              bpmnProcessId,
              workflowModuleId,
              execution.getCurrentActivityId(),
              result.workflowAggregateId(),
              result.created()
                  ? "created"
                  : "existed already");
      return;
    }

    if (!businessKey.equals(result.workflowAggregateId())) {
      throw new IllegalStateException(
          ("The start of BPMN process '%s' of workflow module '%s' at start event '%s' is refused: the "
              + "Camunda 7 process instance '%s' was started with business key '%s', and that key "
              + "cannot be an id of the workflow aggregate this process uses (VanillaBP would have to "
              + "name the workflow '%s' instead and overwrite the key). On Camunda 7 the business key "
              + "IS the workflow aggregate's id. So either start this workflow through ProcessService, "
              + "which writes the id into the key, or start it with a business key which is a valid id "
              + "of that workflow aggregate. Nothing was written - what follows is what Camunda 7 does "
              + "with any failing start.")
              .formatted(
                  bpmnProcessId,
                  workflowModuleId,
                  execution.getCurrentActivityId(),
                  execution.getProcessInstanceId(),
                  businessKey,
                  result.workflowAggregateId()));
    }

    if (result.created()) {
      // worth a line of its own: nobody asked VanillaBP for this workflow, and the
      // application learns about it from here on. INFO rather than WARN because the
      // workflow is in order once it has its aggregate, and once per workflow rather
      // than once per delivery
      log
          .info(
              "Camunda7: '{}' of workflow module '{}' was started past VanillaBP (instance '{}', start "
                  + "event '{}') - the workflow aggregate '{}' was created for it",
              bpmnProcessId,
              workflowModuleId,
              execution.getProcessInstanceId(),
              execution.getCurrentActivityId(),
              result.workflowAggregateId());
      return;
    }

    log
        .debug(
            "Camunda7: the start of '{}' of workflow module '{}' at start event '{}' names workflow "
                + "aggregate '{}', which exists - the workflow was started by the application",
            bpmnProcessId,
            workflowModuleId,
            execution.getCurrentActivityId(),
            result.workflowAggregateId());

  }

  private BpmsInitiatedStartContext contextOf(
      final DelegateExecution execution,
      final String signalName,
      final String processVersion,
      final String businessKey) {

    // what the model set before the start event completed: expressions, input
    // mappings, and for a signal the payload the broadcast carried
    final Map<String, Object> variables = new HashMap<>(execution.getVariables());
    final var startEventId = execution.getCurrentActivityId();

    return new BpmsInitiatedStartContext() {

      @Override
      public String getAdapterId() {
        return taskRegistry.getAdapterId();
      }

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

      @Override
      public Instant getStartInstant() {
        // the engine does not hand a listener the timer's scheduled time, so this
        // is the moment the instance is created. Nothing is lost by that: this
        // listener runs in the transaction which creates the process instance, so a
        // failed attempt takes the aggregate with it and a retry starts from
        // nothing. The repetition guard of the core matters where a BPMS reports a
        // start it already committed - which cannot happen on an embedded engine.
        return Instant.now();
      }

      @Override
      public String getNaturalIdentity() {
        // the name this instance already goes by, which on Camunda 7 is an aggregate's
        // id: the core looks for that aggregate and takes the key over as the id of the
        // one it builds. Where the instance carries no key there is nothing to report,
        // and the id is derived from the trigger as before
        return businessKey;
      }

      @Override
      public String getSignalName() {
        return signalName;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

      @Override
      public String getNativeInstanceId() {
        return execution.getProcessInstanceId();
      }

      @Override
      public boolean runInCurrentTransaction() {
        // an embedded engine sharing the application's transaction: the aggregate
        // has to be written in the transaction which creates the instance. An engine on
        // a datasource of its own runs that transaction on a resource the application's
        // persistence cannot join, so VanillaBP opens its own and the two commit one
        // after the other
        return !taskRegistry.engineRunsOnItsOwnDataSource();
      }

    };

  }

}
