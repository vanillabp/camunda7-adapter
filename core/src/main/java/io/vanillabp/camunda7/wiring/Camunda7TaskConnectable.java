package io.vanillabp.camunda7.wiring;

/**
 * One BPMN task of an executable process wired to a <code>&#64;WorkflowTask</code>
 * method, extracted from the model during <code>wireBpmn</code>. The task
 * definition is the unwrapped expression text (<code>${processPayment}</code>
 * &rarr; <code>processPayment</code>) - VanillaBP's Camunda 7 convention: the
 * task's implementation expression names the task definition.
 *
 * @param workflowModuleId The workflow module (= Camunda tenant) ID
 * @param bpmnProcessId The BPMN process ID
 * @param elementId The BPMN activity ID
 * @param taskDefinition The unwrapped expression text
 * @param type How the BPMN wires the task (expression vs. delegate expression)
 */
public record Camunda7TaskConnectable(
                                      String workflowModuleId,
                                      String bpmnProcessId,
                                      String elementId,
                                      String taskDefinition,
                                      Type type) {

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
    DELEGATE_EXPRESSION
  }

  /**
   * Whether this connectable serves the given EL name evaluated at the given BPMN
   * element.
   */
  public boolean applies(
      final String currentElementId,
      final String propertyName) {

    return elementId.equals(currentElementId) || taskDefinition.equals(propertyName);

  }

}
