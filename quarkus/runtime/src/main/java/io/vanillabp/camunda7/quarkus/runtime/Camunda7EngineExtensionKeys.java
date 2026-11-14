package io.vanillabp.camunda7.quarkus.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Ends the start where the configuration holds a key of the engine's own Quarkus extension,
 * <code>quarkus.camunda.*</code>, and says for each key what to write instead.
 * <p>
 * The adapter builds the engine itself and does not use that extension. So such a key changes
 * nothing. An application which moves from the engine's extension to VanillaBP keeps such keys
 * easily. Quarkus itself only logs a warning about a <code>quarkus.*</code> key which no
 * extension reads, and the start goes on. A key which is not read where it stands ends the start
 * in VanillaBP, so this one does too.
 * <p>
 * A fork of Camunda 7 names the keys of its extension after itself. Operaton's extension reads
 * <code>quarkus.operaton.*</code>, CIB seven's reads <code>quarkus.cibseven.*</code>. The sync
 * recipe of such a fork writes that name into {@link #ENGINE_EXTENSION_NAME}.
 * <p>
 * The keys of the Spring Boot starter, <code>camunda.bpm.*</code>, are not checked on Quarkus.
 * They come from an application of VanillaBP 1 on Spring Boot, and version 1 had no adapter for
 * Quarkus.
 * <p>
 * Keys are compared the way Quarkus maps them: case and dashes do not matter. An environment
 * variable such as <code>QUARKUS_CAMUNDA_DATASOURCE</code> is found as well.
 */
final class Camunda7EngineExtensionKeys {

  private Camunda7EngineExtensionKeys() {
  }

  /**
   * The name in the keys of the Quarkus extension of the engine this adapter embeds:
   * <code>quarkus.&lt;name&gt;.*</code>. A fork of Camunda 7 has its sync recipe write its own
   * name here.
   */
  static final String ENGINE_EXTENSION_NAME = "camunda";

  /**
   * What to say where version 2 has no key for a setting yet.
   */
  private static final String NO_KEY_YET = "VanillaBP has no key for this. Set the value in a "
      + "ProcessEnginePlugin bean of your application. VanillaBP hands every such bean to its engines.";

  private static final String UNKNOWN = "Nothing in VanillaBP reads this key. Remove it.";

  /**
   * What became of a group of keys.
   *
   * @param key The key below <code>quarkus.&lt;name&gt;.</code>. It also covers every key below
   *          it
   * @param successor What to write instead. <code>%1$s</code> is the adapter id, or
   *          <code>&lt;id&gt;</code> where there are several
   */
  private record Rule(
                      String key,
                      String successor) {
  }

  /**
   * What became of the keys of the extension. A more specific key wins over the group around
   * it, whatever the order here. The keys below <code>generic-config</code> are setters of the
   * engine configuration. VanillaBP names its keys after the same setters where it has one.
   */
  private static final List<Rule> RULES = List
      .of(
          new Rule(
              "datasource", "Write 'vanillabp.adapters.%1$s.data-source-name' instead."),
          new Rule(
              "generic-config", NO_KEY_YET),
          new Rule(
              "generic-config.database-schema-update", "Write 'vanillabp.adapters.%1$s.database-schema-update' "
                  + "instead."),
          new Rule(
              "generic-config.history-time-to-live", "Write 'vanillabp.adapters.%1$s.history-time-to-live' instead."),
          new Rule(
              "generic-config.history", "Write 'vanillabp.adapters.%1$s.history-level' instead."),
          new Rule(
              "generic-config.database-table-prefix", "Write 'vanillabp.adapters.%1$s.table-prefix' instead. Note "
                  + "that VanillaBP expects the tables of a prefix to exist already."),
          new Rule(
              "generic-config.default-serialization-format", "Write 'vanillabp.adapters.%1$s.serialization-format' "
                  + "instead."),
          new Rule(
              "generic-config.db-metrics-reporter-activate", "Write 'vanillabp.adapters.%1$s.db-metrics-reporting' "
                  + "instead."),
          new Rule(
              "generic-config.job-executor-deployment-aware", "VanillaBP sets it: the job executor always fetches "
                  + "only the jobs of the deployments of your workflow modules. Remove it."),
          new Rule(
              "job-executor", NO_KEY_YET));

  /**
   * Ends the start if the configuration holds a key of the engine's Quarkus extension. All
   * such keys are named at once, so one restart is enough to fix them.
   *
   * @param adapterIds The ids of the configured adapters of type <code>camunda7</code>
   * @param propertyNames Every property name the configuration knows
   * @throws IllegalStateException Naming every such key and what to write instead
   */
  static void endTheStartOnKeysOfTheEngineExtension(
      final Collection<String> adapterIds,
      final Iterable<String> propertyNames) {

    endTheStartOnExtensionKeys(ENGINE_EXTENSION_NAME, adapterIds, propertyNames);

  }

  /**
   * Ends the start if the configuration holds a key of the Quarkus extension of the given
   * name.
   *
   * @param extensionName The name in the keys of the extension, see
   *          {@link #ENGINE_EXTENSION_NAME}
   * @param adapterIds The ids of the configured adapters of type <code>camunda7</code>
   * @param propertyNames Every property name the configuration knows
   * @throws IllegalStateException Naming every such key and what to write instead
   */
  static void endTheStartOnExtensionKeys(
      final String extensionName,
      final Collection<String> adapterIds,
      final Iterable<String> propertyNames) {

    final var properties = "quarkus.%s.".formatted(extensionName);
    final var environment = "QUARKUS_%s_".formatted(extensionName.toUpperCase(Locale.ROOT));
    final var findings = new LinkedHashMap<String, String>();
    for (final var name : propertyNames) {
      if ((name == null) || findings.containsKey(name)) {
        continue;
      }
      final String key;
      if (name.startsWith(environment)) {
        key = "_"
            + name.substring(environment.length());
      } else if (normalized(name).startsWith(properties)) {
        key = normalized(name).substring(properties.length());
      } else {
        continue;
      }
      findings.put(name, successorOf(key, adapterIds));
    }
    if (findings.isEmpty()) {
      return;
    }
    final var lines = new ArrayList<String>();
    lines.add("The configuration has keys of the Quarkus extension of the Camunda 7 engine ('%s*'). VanillaBP "
        .formatted(properties)
        + "builds the engine itself and does not use that extension. So these keys would change nothing. "
        + "Change each of them:");
    findings.forEach((
        name,
        successor) -> lines.add("  - '%s': %s".formatted(name, successor)));
    if (adapterIds.size() > 1) {
      lines.add("Where a key names '<id>', write it once for each Camunda 7 adapter id that needs it (%s)."
          .formatted(String.join(", ", adapterIds)));
    }
    throw new IllegalStateException(String.join("\n", lines));

  }

  private static String successorOf(
      final String key,
      final Collection<String> adapterIds) {

    final var adapterId = adapterIds.size() == 1
        ? adapterIds
            .iterator()
            .next()
        : "<id>";
    return RULES
        .stream()
        .filter(rule -> covers(rule.key(), key))
        // the most specific rule wins: 'generic-config.history' over 'generic-config'
        .max(Comparator.comparingInt(rule -> rule
            .key()
            .length()))
        .map(rule -> rule
            .successor()
            .formatted(adapterId))
        .orElse(UNKNOWN);

  }

  /**
   * Whether a key of a rule covers a normalized key: the key itself or a key below it. A key
   * which starts with an underscore comes from an environment variable. It cannot be split into
   * parts without guessing, so it is compared in the form of an environment variable.
   */
  private static boolean covers(
      final String ruleKey,
      final String key) {

    if (key.startsWith("_")) {
      final var variable = "_"
          + ruleKey
              .replace('.', '_')
              .replace('-', '_')
              .toUpperCase(Locale.ROOT);
      return key.equals(variable) || key.startsWith(variable
          + "_");
    }
    final var normalizedRuleKey = normalized(ruleKey);
    return key.equals(normalizedRuleKey) || key.startsWith(normalizedRuleKey
        + ".");

  }

  private static String normalized(
      final String text) {

    return text
        .replace("-", "")
        .replace("\"", "")
        .toLowerCase(Locale.ROOT);

  }

}
