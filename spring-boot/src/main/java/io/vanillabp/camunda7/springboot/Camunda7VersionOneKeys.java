package io.vanillabp.camunda7.springboot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ends the start where the configuration still holds a key of Camunda's Spring Boot starter,
 * <code>camunda.bpm.*</code>, and says for each key what to write instead.
 * <p>
 * In VanillaBP 1 the starter built the engine and read these keys. Version 2 builds the engine
 * itself and reads only <code>vanillabp.adapters.&lt;id&gt;.*</code>. A key left over from
 * version 1 would change nothing, and nobody would be told. A key which is not read where it
 * stands ends the start in VanillaBP, so this one does too.
 * <p>
 * A key which something on the classpath really reads is left alone. The module
 * <code>camunda7-adapter-spring-boot-webapps</code> hands <code>camunda.bpm.webapp.*</code> to
 * Camunda's web applications and reads <code>camunda.bpm.enabled</code> itself. The starter it
 * brings writes <code>camunda.bpm.version</code>, <code>camunda.bpm.is-enterprise</code> and
 * <code>camunda.bpm.formatted-version</code> into the environment on its own.
 * <p>
 * Keys are compared the way Spring Boot binds them: case, dashes and underscores do not matter.
 * An environment variable such as <code>CAMUNDA_BPM_HISTORY_LEVEL</code> is found as well.
 */
final class Camunda7VersionOneKeys {

  private Camunda7VersionOneKeys() {
  }

  /**
   * The class of the webapps module. It is named as text, because this module does not
   * depend on that one. <code>Camunda7WebappsVersionOneKeysTest</code> in the webapps module
   * fails if the class is renamed.
   */
  static final String WEBAPPS_MODULE_CLASS = "io.vanillabp.camunda7.springboot.webapps.Camunda7WebappsAutoConfiguration";

  private static final String PREFIX = "camunda.bpm.";

  /**
   * What to say where version 2 has no key for a setting yet. When a story adds one, the
   * keys it covers get a {@link Rule} of their own which names it.
   */
  private static final String NO_KEY_YET = "Version 2 has no key for this yet. Set the value in a "
      + "ProcessEnginePlugin bean of your application. VanillaBP hands every such bean to its engines.";

  private static final String REMOVE_IT = "Remove it.";

  /**
   * What became of a group of keys.
   *
   * @param key The key below <code>camunda.bpm.</code>. It also covers every key below it
   * @param successor What to write instead. <code>%1$s</code> is the adapter id, or
   *          <code>&lt;id&gt;</code> where there are several
   */
  private record Rule(
                      String key,
                      String successor) {
  }

  /**
   * What became of every key of the starter. A more specific key wins over the group around
   * it, whatever the order here.
   */
  private static final List<Rule> RULES = List
      .of(
          new Rule(
              "history-level", "Write 'vanillabp.adapters.%1$s.history-level' instead."),
          new Rule(
              "history-level-default", "Write 'vanillabp.adapters.%1$s.history-level' instead."),
          new Rule(
              "database.schema-update", "Write 'vanillabp.adapters.%1$s.database-schema-update' instead."),
          new Rule(
              "database.table-prefix", "Write 'vanillabp.adapters.%1$s.table-prefix' instead. Note that VanillaBP "
                  + "expects the tables of a prefix to exist already."),
          new Rule(
              "database.type", "The engine finds the type of the database itself. "
                  + REMOVE_IT),
          new Rule(
              "database", NO_KEY_YET),
          new Rule(
              "default-serialization-format", "Write 'vanillabp.adapters.%1$s.serialization-format' instead."),
          new Rule(
              "metrics.db-reporter-activate", "Write 'vanillabp.adapters.%1$s.db-metrics-reporting' instead."),
          new Rule(
              "metrics", NO_KEY_YET),
          new Rule(
              "admin-user", "Write it below 'vanillabp.adapters.%1$s.webapps.admin-user' instead, with the module "
                  + "camunda7-adapter-spring-boot-webapps on the classpath."),
          new Rule(
              "job-execution", NO_KEY_YET),
          new Rule(
              "job-executor-acquire-by-priority", NO_KEY_YET),
          new Rule(
              "generic-properties", NO_KEY_YET),
          new Rule(
              "authorization", NO_KEY_YET),
          new Rule(
              "default-number-of-retries", NO_KEY_YET),
          new Rule(
              "id-generator", NO_KEY_YET),
          new Rule(
              "eventing", "The engine publishes no Spring events in version 2, on purpose. Such a listener would work "
                  + "with the engine behind VanillaBP's back. Use @WorkflowTask, @TaskEvent and @WorkflowEnded "
                  + "instead, and remove the key."),
          new Rule(
              "process-engine-name", "VanillaBP names the engine 'vanillabp-camunda7-%1$s'. "
                  + REMOVE_IT),
          new Rule(
              "generate-unique-process-engine-name", "VanillaBP names the engine 'vanillabp-camunda7-%1$s'. "
                  + REMOVE_IT),
          new Rule(
              "generate-unique-process-application-name", "VanillaBP uses no process applications. "
                  + REMOVE_IT),
          new Rule(
              "auto-deployment-enabled", "VanillaBP deploys the models of each workflow module itself. Name "
                  + "where they are with 'vanillabp.workflow-modules.<module>.adapters.%1$s.resources-location', "
                  + "and remove this key."),
          new Rule(
              "deployment-resource-pattern", "VanillaBP deploys the models of each workflow module itself. Name "
                  + "where they are with 'vanillabp.workflow-modules.<module>.adapters.%1$s.resources-location' "
                  + "instead."),
          new Rule(
              "filter", "VanillaBP creates no filters for the tasklist. Create them in the tasklist, and remove "
                  + "the key."),
          new Rule(
              "license-file", "VanillaBP does not read it. "
                  + REMOVE_IT),
          new Rule(
              "rest-api", "The engine's REST API is not part of the adapter. "
                  + REMOVE_IT),
          new Rule(
              "webapp", "Add the module camunda7-adapter-spring-boot-webapps, which serves the web "
                  + "applications and reads these keys, or remove the key."),
          new Rule(
              "enabled", "VanillaBP builds an engine for every adapter id of type camunda7. "
                  + REMOVE_IT
                  + " An application without an engine configures no such adapter id."));

  private static final String UNKNOWN = "Nothing in VanillaBP 2 reads this key. "
      + REMOVE_IT;

  /**
   * The keys the starter of the webapps module writes into the environment itself. They are
   * never the application's.
   */
  private static final List<String> WRITTEN_BY_THE_STARTER = List.of("version", "is-enterprise", "formatted-version");

  /**
   * The keys which the webapps module reads, where it is on the classpath.
   */
  private static final List<String> READ_BY_THE_WEBAPPS_MODULE = List.of("webapp", "enabled");

  /**
   * Whether the module <code>camunda7-adapter-spring-boot-webapps</code> is on the
   * classpath.
   *
   * @param classLoader The class loader to ask
   * @return Whether the module is there
   */
  static boolean webappsModuleIsPresent(
      final ClassLoader classLoader) {

    return org.springframework.util.ClassUtils.isPresent(WEBAPPS_MODULE_CLASS, classLoader);

  }

  /**
   * Ends the start if the configuration holds a key of the starter which nobody reads. All
   * such keys are named at once, so one restart is enough to fix them.
   *
   * @param adapterIds The ids of the configured adapters of type <code>camunda7</code>
   * @param propertyNames Every property name the environment knows
   * @param webappsModuleIsPresent Whether the webapps module is on the classpath
   * @throws IllegalStateException Naming every such key and what to write instead
   */
  static void endTheStartOnKeysOfVersionOne(
      final Collection<String> adapterIds,
      final Iterable<String> propertyNames,
      final boolean webappsModuleIsPresent) {

    final var findings = new LinkedHashMap<String, String>();
    for (final var name : propertyNames) {
      if ((name == null) || findings.containsKey(name)) {
        continue;
      }
      final var key = keyBelowThePrefix(name);
      if ((key == null) || isLeftAlone(key, webappsModuleIsPresent)) {
        continue;
      }
      findings.put(name, successorOf(key, adapterIds));
    }
    if (findings.isEmpty()) {
      return;
    }
    throw new IllegalStateException(messageFor(findings, adapterIds));

  }

  private static String messageFor(
      final Map<String, String> findings,
      final Collection<String> adapterIds) {

    final var lines = new ArrayList<String>();
    lines.add("The configuration still has keys of Camunda's Spring Boot starter, which VanillaBP 1 used to "
        + "build the engine. VanillaBP 2 builds the Camunda 7 engine itself and does not read them. So they "
        + "would change nothing. Change each of them:");
    findings.forEach((
        name,
        successor) -> lines.add("  - '%s': %s".formatted(name, successor)));
    if (adapterIds.size() > 1) {
      lines.add("Where a key names '<id>', write it once for each Camunda 7 adapter id that needs it (%s)."
          .formatted(String.join(", ", adapterIds)));
    }
    return String.join("\n", lines);

  }

  private static boolean isLeftAlone(
      final String key,
      final boolean webappsModuleIsPresent) {

    if (WRITTEN_BY_THE_STARTER
        .stream()
        .anyMatch(written -> covers(written, key))) {
      return true;
    }
    return webappsModuleIsPresent && READ_BY_THE_WEBAPPS_MODULE
        .stream()
        .anyMatch(read -> covers(read, key));

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
        // the most specific rule wins: 'database.type' over 'database'
        .max(Comparator.comparingInt(rule -> rule
            .key()
            .length()))
        .map(rule -> rule
            .successor()
            .formatted(adapterId))
        .orElse(UNKNOWN);

  }

  /**
   * The key below <code>camunda.bpm.</code> in its normalized form, or <code>null</code> for
   * a property name which is not such a key. The normalized form has no case, no dashes and
   * no underscores within a part. An environment variable cannot be split into parts without
   * guessing. Its normalized form keeps the underscores as separators, and {@link #covers}
   * compares it in the same form.
   */
  private static String keyBelowThePrefix(
      final String name) {

    if (name.startsWith("CAMUNDA_BPM_")) {
      return "_"
          + name.substring("CAMUNDA_BPM_".length());
    }
    final var normalized = normalized(name);
    if (!normalized.startsWith(PREFIX)) {
      return null;
    }
    return normalized.substring(PREFIX.length());

  }

  /**
   * Whether a key of a rule covers a normalized key: the key itself, a key below it, or an
   * element of it.
   */
  private static boolean covers(
      final String ruleKey,
      final String key) {

    if (key.startsWith("_")) {
      // an environment variable, compared in its own form
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
        + ".") || key.startsWith(
            normalizedRuleKey
                + "[");

  }

  private static String normalized(
      final String text) {

    return text
        .replace("-", "")
        .replace("_", "")
        .toLowerCase(Locale.ROOT);

  }

}
