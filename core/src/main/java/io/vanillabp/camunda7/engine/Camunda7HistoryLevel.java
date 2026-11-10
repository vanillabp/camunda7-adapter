package io.vanillabp.camunda7.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.HistoryLevel;

/**
 * How much history the engine writes, for both platforms. The key is
 * <code>vanillabp.adapters.&lt;id&gt;.history-level</code>, and its default is <code>full</code>.
 * Version 1 of VanillaBP had the engine built by Camunda's Spring Boot starter, and the starter
 * uses <code>full</code>. The engine itself would use <code>audit</code>.
 * <p>
 * The engine writes the level into the database on its first start and compares it on every
 * later start. If the two differ, the engine does not start. So a
 * database which version 1 set up only starts with <code>full</code>, or with
 * <code>auto</code>, which takes the level the database stores. See decision 58 in the
 * repository's DECISIONS.md for why the default is the one of version 1.
 * <p>
 * {@link #applyTo} hands the value to the engine before it is built. The engine itself decides
 * which names it knows, because an engine plugin or an engine customizer may add a level of its
 * own (<code>customHistoryLevels</code>). {@link #explainWhyTheEngineDidNotStart} turns the two
 * failures of the engine into a message which names the key: an unknown level, and a level
 * which differs from the one in the database.
 */
public final class Camunda7HistoryLevel {

  /** The default of the key, which is what version 1 of VanillaBP had. */
  public static final String DEFAULT = ProcessEngineConfiguration.HISTORY_FULL;

  /**
   * The levels every engine knows, in the order of how much they write. <code>auto</code> is
   * not a level of its own. It takes the level which the database stores.
   */
  private static final List<String> BUILT_IN = List
      .of(
          ProcessEngineConfiguration.HISTORY_NONE,
          ProcessEngineConfiguration.HISTORY_ACTIVITY,
          ProcessEngineConfiguration.HISTORY_AUDIT,
          ProcessEngineConfiguration.HISTORY_FULL,
          ProcessEngineConfiguration.HISTORY_AUTO);

  /**
   * The start of the message the engine throws when the configured level differs from the
   * stored one. The engine has no exception type and no code for this case, so its message is
   * the only thing to look for. {@code Camunda7HistoryLevelTest} fails if a new engine
   * version words it differently.
   */
  private static final String MISMATCH = "historyLevel mismatch";

  /**
   * The stored level in that message. The engine prints a level as
   * <code>HistoryLevelFull(name=full, id=3)</code>.
   */
  private static final Pattern STORED_LEVEL = Pattern.compile("database says .*?name=([^,)]+)");

  private Camunda7HistoryLevel() {
    // static helper
  }

  /**
   * Hands the configured level to an engine which is about to be built. It is called for every
   * adapter id.
   *
   * @param adapterId The adapter id whose engine is being built
   * @param configuration The engine configuration, before the engine is built
   * @param properties The adapter id's engine settings
   * @throws Camunda7StartupExplanation If the value is empty, or the old name
   *         <code>variable</code>, which the engine still reads as <code>activity</code>
   */
  public static void applyTo(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration,
      final Camunda7EngineProperties properties) {

    final var configured = properties.getHistoryLevel();
    // the engine reads 'variable' as 'activity' and only warns about it. A key of version 2
    // should not start with a name the engine itself calls deprecated
    if ((configured == null) || configured.isBlank() || ProcessEngineConfiguration.HISTORY_VARIABLE
        .equalsIgnoreCase(configured.trim())) {
      throw notALevel(adapterId, configured, BUILT_IN);
    }
    configuration.setHistory(configured.trim());

  }

  /**
   * Called with what the engine threw while it was built. If the history level caused it, this
   * throws an exception whose message names the key and the fix. Otherwise it returns, and the
   * caller throws the original.
   *
   * @param adapterId The adapter id whose engine did not start
   * @param configuration The configuration the engine was built from
   * @param properties The adapter id's engine settings
   * @param failure What building the engine threw
   * @throws Camunda7StartupExplanation If the history level is why the engine did not start
   */
  public static void explainWhyTheEngineDidNotStart(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration,
      final Camunda7EngineProperties properties,
      final Throwable failure) {

    final var configured = properties.getHistoryLevel();
    if (configured == null) {
      return;
    }

    // the engine fills the list of the levels it knows before it looks for the configured
    // one. So a list without that name means the engine stopped because of it. A list which
    // is still empty means the engine stopped before it got that far
    final var known = configuration.getHistoryLevels();
    if ((known != null) && !ProcessEngineConfiguration.HISTORY_AUTO.equalsIgnoreCase(configured.trim()) && known
        .stream()
        .noneMatch(level -> level.getName().equalsIgnoreCase(configured.trim()))) {
      final var allowed = new ArrayList<>(BUILT_IN);
      known
          .stream()
          .map(HistoryLevel::getName)
          .filter(name -> !allowed.contains(name))
          .forEach(allowed::add);
      throw notALevel(adapterId, configured, allowed, failure);
    }

    final var mismatch = mismatchIn(failure);
    if (mismatch == null) {
      return;
    }
    final var matcher = STORED_LEVEL.matcher(mismatch.getMessage());
    final var stored = matcher.find()
        ? matcher.group(1)
        : null;
    throw new Camunda7StartupExplanation(
        """
            Camunda 7 adapter '%s' could not start its engine, because the database was set up with \
            another history level. The database stores %s, but 'vanillabp.adapters.%s.history-level' \
            is '%s'. The engine writes its history level into the database on its first start, and \
            every later start has to use the same level."""
            .formatted(
                adapterId,
                stored == null
                    ? "another level"
                    : "'%s'".formatted(stored),
                adapterId,
                configured.trim()), """
                    Set 'vanillabp.adapters.%s.history-level: %s' to keep the level of this database. Or set \
                    it to 'auto', which always takes the level the database stores. VanillaBP 1 set up its \
                    databases with 'full'."""
                    .formatted(
                        adapterId,
                        stored == null
                            ? "<the stored level>"
                            : stored), failure);

  }

  private static Camunda7StartupExplanation notALevel(
      final String adapterId,
      final String configured,
      final List<String> allowed) {

    return notALevel(adapterId, configured, allowed, null);

  }

  private static Camunda7StartupExplanation notALevel(
      final String adapterId,
      final String configured,
      final List<String> allowed,
      final Throwable failure) {

    return new Camunda7StartupExplanation(
        """
            Camunda 7 adapter '%s' could not start its engine, because 'vanillabp.adapters.%s.history-level' \
            is '%s', which is not a history level of this engine."""
            .formatted(
                adapterId,
                adapterId,
                configured == null
                    ? ""
                    : configured), """
                        Use one of: %s. The default is '%s', as in VanillaBP 1. 'auto' takes the level the \
                        database stores, and 'audit' for a new database."""
                        .formatted(
                            String.join(", ", allowed),
                            DEFAULT), failure);

  }

  /**
   * The exception in the chain of causes which says that the configured level differs from
   * the stored one, or <code>null</code>.
   */
  private static ProcessEngineException mismatchIn(
      final Throwable failure) {

    final Set<Throwable> seen = new HashSet<>();
    for (var current = failure; (current != null) && seen.add(current); current = current.getCause()) {
      if ((current instanceof ProcessEngineException engineException) && (current.getMessage() != null) && current
          .getMessage()
          .toLowerCase(Locale.ROOT)
          .startsWith(MISMATCH.toLowerCase(Locale.ROOT))) {
        return engineException;
      }
    }
    return null;

  }

}
