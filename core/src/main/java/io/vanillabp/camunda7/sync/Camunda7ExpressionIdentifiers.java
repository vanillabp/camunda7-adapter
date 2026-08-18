package io.vanillabp.camunda7.sync;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CompletionCondition;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.LoopCardinality;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.TimerEventDefinition;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

/**
 * Reads the identifiers the expressions of a BPMN process rely on - the input of story
 * 66's startup check: a name which is an attribute of the workflow aggregate but is not
 * shared with the BPMS always evaluates to <code>null</code>, and Camunda 7 then behaves
 * as if the condition was false. No exception, no log line, the default flow.
 * <p>
 * What is read: conditions of sequence flows and conditional events, the definitions of
 * timers, and the cardinality, collection and completion condition of multi-instance
 * elements. Everything else a model can carry either names a wired task (which the EL
 * resolver serves) or is an input mapping, where a missing value shows up as an incident
 * rather than as a silent decision.
 * <p>
 * The extraction is deliberately conservative: it collects TOP-LEVEL names of
 * <code>${...}</code> and <code>#{...}</code> expressions and skips what is clearly not a
 * variable - EL keywords, function calls, and members read from something else
 * (<code>a.b</code> yields <code>a</code>, never <code>b</code>). An expression it cannot
 * make sense of contributes nothing, because a wrong warning about a model which works is
 * worse than a missing one.
 */
public final class Camunda7ExpressionIdentifiers {

  /**
   * The <code>${...}</code> and <code>#{...}</code> blocks of an attribute value.
   */
  private static final Pattern EXPRESSION = Pattern.compile("[#$]\\{([^}]*)\\}");

  /**
   * A Java-ish identifier, possibly followed by what makes it a member access or a call.
   */
  private static final Pattern IDENTIFIER = Pattern
      .compile("(?<![\\w.$:])([A-Za-z_$][\\w$]*)\\s*(?<call>[(:])?");

  /**
   * Names which are part of the language rather than a variable.
   */
  private static final Set<String> KEYWORDS = Set
      .of(
          "true", "false", "null", "empty", "and", "or", "not", "div", "mod", "instanceof", "eq",
          "ne", "lt", "gt", "le", "ge", "execution", "task", "authenticatedUserId", "currentUser",
          "currentUserGroups", "dateTime", "now", "loopCounter", "nrOfInstances",
          "nrOfActiveInstances", "nrOfCompletedInstances");

  private Camunda7ExpressionIdentifiers() {
  }

  /**
   * The identifiers read by the expressions of one BPMN process, mapped to where they
   * were read - the element's ID and the expression itself, so a message can name both.
   *
   * @param model The deployed model
   * @param scopedBpmnProcessId The process ID as the engine knows it
   * @return The identifiers with their origin, in the order found
   */
  public static Map<String, Origin> of(
      final BpmnModelInstance model,
      final String scopedBpmnProcessId) {

    final var identifiers = new LinkedHashMap<String, Origin>();
    final var process = model.getModelElementById(scopedBpmnProcessId);
    if (!(process instanceof Process)) {
      return identifiers;
    }
    collect(model, ConditionExpression.class, process, identifiers);
    collect(model, TimerEventDefinition.class, process, identifiers);
    collect(model, LoopCardinality.class, process, identifiers);
    collect(model, CompletionCondition.class, process, identifiers);
    collect(model, MultiInstanceLoopCharacteristics.class, process, identifiers);
    return identifiers;

  }

  /**
   * Where an identifier was read.
   *
   * @param elementId The ID of the BPMN element carrying the expression
   * @param expression The expression as the model has it
   */
  public record Origin(String elementId, String expression) {
  }

  /**
   * Collects from the elements of one type, reading the texts a model puts an expression
   * into - the element's own text (a condition, a timer, a cardinality) and the Camunda
   * attribute naming a multi-instance collection.
   */
  private static void collect(
      final BpmnModelInstance model,
      final Class<? extends ModelElementInstance> type,
      final ModelElementInstance process,
      final Map<String, Origin> identifiers) {

    model
        .getModelElementsByType(model.getModel().getType(type))
        .stream()
        .filter(element -> belongsTo(element, process))
        .forEach(element -> {
          final var elementId = elementIdOf(element);
          textsOf(element).forEach(text -> identifiersOf(text)
              .forEach(name -> identifiers.putIfAbsent(name, new Origin(elementId, text.trim()))));
        });

  }

  /**
   * The texts of one element which may carry an expression.
   */
  private static Set<String> textsOf(
      final ModelElementInstance element) {

    final var texts = new LinkedHashSet<String>();
    if ((element.getTextContent() != null) && !element.getTextContent().isBlank()) {
      texts.add(element.getTextContent());
    }
    if (element instanceof TimerEventDefinition timer) {
      // a timer holds its definition in a child element
      addIfPresent(texts, timer.getTimeDuration());
      addIfPresent(texts, timer.getTimeDate());
      addIfPresent(texts, timer.getTimeCycle());
    }
    if (element instanceof MultiInstanceLoopCharacteristics multiInstance) {
      // the collection a multi-instance element iterates: an expression naming an
      // attribute of the aggregate is the common case
      if (multiInstance.getCamundaCollection() != null) {
        texts.add(multiInstance.getCamundaCollection());
      }
    }
    return texts;

  }

  private static void addIfPresent(
      final Set<String> texts,
      final ModelElementInstance element) {

    if ((element != null) && (element.getTextContent() != null) && !element.getTextContent().isBlank()) {
      texts.add(element.getTextContent());
    }

  }

  /**
   * Whether the element sits inside the given process - a file may hold several.
   */
  private static boolean belongsTo(
      final ModelElementInstance element,
      final ModelElementInstance process) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      if (candidate == process) {
        return true;
      }
    }
    return false;

  }

  /**
   * The ID of the element itself or of the closest ancestor having one - a condition
   * expression carries no ID, its sequence flow does.
   */
  private static String elementIdOf(
      final ModelElementInstance element) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      final var id = candidate
          .getDomElement()
          .getAttribute("id");
      if ((id != null) && !id.isBlank()) {
        return id;
      }
    }
    return "unknown element";

  }

  /**
   * The top-level identifiers of one expression text.
   *
   * @param text The attribute value or element text, possibly without any expression
   * @return The identifiers found
   */
  static Set<String> identifiersOf(
      final String text) {

    final var names = new LinkedHashSet<String>();
    if (text == null) {
      return names;
    }
    final var expressions = EXPRESSION.matcher(text);
    while (expressions.find()) {
      // string literals are no identifiers: '${execution.getVariable('x')}' reads a
      // variable named by the engine's API, not one this check is about
      final var body = expressions
          .group(1)
          .replaceAll("'[^']*'", "''")
          .replaceAll("\"[^\"]*\"", "\"\"");
      final var candidates = IDENTIFIER.matcher(body);
      while (candidates.find()) {
        if (candidates.group("call") != null) {
          // a function call ('fn(...)') or a namespace prefix ('fn:x(...)'), not a
          // variable
          continue;
        }
        final var name = candidates.group(1);
        if (KEYWORDS.contains(name)) {
          continue;
        }
        names.add(name);
      }
    }
    return names;

  }

}
