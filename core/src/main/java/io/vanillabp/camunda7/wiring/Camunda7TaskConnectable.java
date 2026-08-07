package io.vanillabp.camunda7.wiring;

/**
 * One BPMN task of an executable process wired to a <code>&#64;WorkflowTask</code>
 * method, extracted from the model during <code>wireBpmn</code>. The task
 * definition is the unwrapped expression text (<code>${processPayment}</code>
 * &rarr; <code>processPayment</code>) - VanillaBP's Camunda 7 convention: the
 * task's implementation expression names the task definition.
 *
 * @param workflowModuleId The workflow module ID (the Camunda tenant, unless the
 *          module's name-clash-avoidance mode uses no tenant)
 * @param bpmnProcessId The PLAIN BPMN process ID - what the core's registries are
 *          keyed by
 * @param scopedBpmnProcessId The BPMN process ID AS THE ENGINE KNOWS IT (equal to
 *          {@code bpmnProcessId} unless the module's identifiers are prefixed,
 *          story 35)
 * @param elementId The BPMN activity ID
 * @param taskDefinition The unwrapped expression text
 * @param type How the BPMN wires the task (expression vs. delegate expression)
 */
public record Camunda7TaskConnectable(
                                      String workflowModuleId,
                                      String bpmnProcessId,
                                      String scopedBpmnProcessId,
                                      String elementId,
                                      String taskDefinition,
                                      Type type) {

  /**
   * Convenience constructor for an unscoped process (the engine knows the plain id).
   */
  public Camunda7TaskConnectable(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String elementId,
      final String taskDefinition,
      final Type type) {

    this(workflowModuleId, bpmnProcessId, bpmnProcessId, elementId, taskDefinition, type);

  }

  public enum Type {
    /**
     * <code>camunda:expression</code>: the expression is evaluated - the handler
     * runs during evaluation and the task completes when the expression returns.
     * Tasks of <code>&#64;TaskId</code> methods cannot be wired this way (they
     * could never stay open).
     */
    EXPRESSION,
    /**
     * <code>camunda:delegateExpression</code>: the expression yields the
     * adapter's {@link Camunda7WorkflowTaskBehavior} - the task stays open for
     * <code>&#64;TaskId</code> methods and is completed asynchronously later.
     */
    DELEGATE_EXPRESSION,
    /**
     * A BPMN user task (story 24): the task definition is the task's
     * <code>camunda:formKey</code>; the handler (if any) is notified via task
     * listeners (CREATED/CANCELED) and never completes the task on return.
     */
    USER_TASK
  }

  /**
   * Whether this connectable serves the given EL name evaluated at the given BPMN
   * element.
   */
  public boolean applies(
      final String currentElementId,
      final String propertyName) {

    return elementId.equals(currentElementId) || ((taskDefinition != null) && taskDefinition.equals(propertyName));

  }

}
