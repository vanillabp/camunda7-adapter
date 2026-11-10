package io.vanillabp.camunda7.engine;

/**
 * The adapter's own explanation of why the application cannot start, in two parts: what is
 * wrong, and what the developer does about it.
 * <p>
 * Such an explanation is the message a developer has to read first. The platforms make sure
 * it is: on Spring Boot a failure analyzer puts it under "APPLICATION FAILED TO START",
 * although it sits deep in a chain of bean creation exceptions. On Quarkus the engine producer
 * throws it as it is and does not wrap it into a message of its own.
 * <p>
 * The type extends {@link IllegalStateException}, so code which catches that keeps working.
 * The message is both parts in one text, as it was before the type existed.
 */
public class Camunda7StartupExplanation extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** What is wrong, in the words a developer reads first. */
  private final String description;

  /** What the developer changes so that the next start works. */
  private final String action;

  /**
   * Builds the explanation.
   *
   * @param description What is wrong
   * @param action What the developer changes so that the next start works
   * @param cause What the adapter explains, or <code>null</code>
   */
  public Camunda7StartupExplanation(
      final String description,
      final String action,
      final Throwable cause) {

    super("%s %s".formatted(description, action), cause);
    this.description = description;
    this.action = action;

  }

  /**
   * What is wrong.
   *
   * @return The first part of the message
   */
  public String getDescription() {
    return description;
  }

  /**
   * What the developer changes so that the next start works.
   *
   * @return The second part of the message
   */
  public String getAction() {
    return action;
  }

  /**
   * The adapter's explanation in a chain of causes, or <code>null</code>. The first one found
   * wins, counted from the outside.
   *
   * @param failure The outermost exception
   * @return The explanation, or <code>null</code> if the chain has none
   */
  public static Camunda7StartupExplanation in(
      final Throwable failure) {

    final var seen = new java.util.HashSet<Throwable>();
    for (var current = failure; (current != null) && seen.add(current); current = current.getCause()) {
      if (current instanceof Camunda7StartupExplanation explanation) {
        return explanation;
      }
    }
    return null;

  }

}
