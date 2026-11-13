package io.vanillabp.camunda7.springboot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * <code>camunda7-adapter-spring-boot-webapps</code> hands <code>webapp.*</code> below the prefix
 * of the engine's starter to the web applications, and reads <code>enabled</code> there itself.
 * The starter it brings writes <code>version</code>, <code>is-enterprise</code> and
 * <code>formatted-version</code> below its prefix into the environment on its own. Camunda's
 * starter of the external task client, <code>camunda-bpm-spring-boot-starter-external-task-client</code>,
 * reads <code>camunda.bpm.client.*</code>. An application may use it next to the adapter, to
 * work on the external tasks of another engine.
 * <p>
 * A key which a webapps module of VanillaBP sets as its default is not the application's
 * either. Such a module may set <code>camunda.bpm.enabled</code> to <code>false</code>, so
 * that Camunda's starter builds no engine of its own. An application which moves to a fork of
 * Camunda 7 has the webapps module of the old adapter next to the adapter of the fork. The
 * fork's copy of this check does not know that module. Without this rule it would end the start
 * on a key which nobody wrote. So the check does not read the property sources which such a
 * module adds, see {@link #isAddedByAWebappsModule}. The same key written by the application
 * still ends the start.
 * <p>
 * A fork of Camunda 7 may have renamed the keys of its own starter. Operaton's starter reads
 * <code>operaton.bpm.*</code>. Such keys are not read either, because no starter builds the
 * engine. So they end the start as well, with the same hints, see {@link #ENGINE_STARTER_NAME}.
 * The keys of version 1, <code>camunda.bpm.*</code>, are still checked next to them. The keys
 * which the webapps module of this adapter reads are left alone below the prefix of the
 * engine's starter only, because that is the prefix the engine's web applications read.
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

  /**
   * The class of Camunda's starter of the external task client which binds
   * <code>camunda.bpm.client.*</code>. It is named as text, because the adapter does not
   * depend on that starter. <code>Camunda7ExternalTaskClientVersionOneKeysTest</code> fails if
   * the class is renamed.
   */
  static final String EXTERNAL_TASK_CLIENT_CLASS = "org.camunda.bpm.client.spring.boot.starter.ClientProperties";

  /** The name in the keys of Camunda's starter, which version 1 used: <code>camunda.bpm.*</code>. */
  private static final String VERSION_ONE_STARTER_NAME = "camunda";

  /**
   * The name in the keys of the Spring Boot starter of the engine this adapter embeds:
   * <code>&lt;name&gt;.bpm.*</code>. For Camunda 7 it is the name of version 1. A fork of
   * Camunda 7 whose starter reads keys of another name has its sync recipe write that name here,
   * Operaton's recipe <code>operaton</code>. Its keys then end the start next to the keys of
   * version 1. A fork which kept the name, like CIB seven, changes nothing here.
   */
  static final String ENGINE_STARTER_NAME = "camunda";

  /**
   * A prefix of the keys this class checks.
   *
   * @param properties The prefix of a property name, for example <code>camunda.bpm.</code>
   * @param environment The prefix of an environment variable, for example
   *          <code>CAMUNDA_BPM_</code>
   * @param ofTheEngine Whether the starter of the engine this adapter embeds reads the keys
   * @param ofVersionOne Whether these are the keys of version 1
   */
  private record Prefix(
                        String properties,
                        String environment,
                        boolean ofTheEngine,
                        boolean ofVersionOne) {

    static Prefix of(
        final String starterName,
        final boolean ofTheEngine,
        final boolean ofVersionOne) {

      return new Prefix(
          "%s.bpm.".formatted(starterName), "%s_BPM_"
              .formatted(starterName.toUpperCase(Locale.ROOT)), ofTheEngine, ofVersionOne);

    }

    /** Whether the keys are those of an engine starter which renamed the keys of version 1. */
    boolean ofTheEngineOnly() {
      return ofTheEngine && !ofVersionOne;
    }

    /** Whether the keys are those of version 1 while the engine's starter reads other ones. */
    boolean ofVersionOneOnly() {
      return ofVersionOne && !ofTheEngine;
    }

  }

  /**
   * A key found below one of the prefixes.
   *
   * @param prefix The prefix it was found below
   * @param key The key below the prefix, normalized (see {@link #keyBelow})
   */
  private record Found(
                       Prefix prefix,
                       String key) {
  }

  /**
   * The prefixes to check: the one of version 1, and the one of the engine's starter where it
   * has another name.
   */
  private static List<Prefix> prefixesFor(
      final String engineStarterName) {

    if (VERSION_ONE_STARTER_NAME.equals(engineStarterName)) {
      return List.of(Prefix.of(VERSION_ONE_STARTER_NAME, true, true));
    }
    return List.of(Prefix.of(VERSION_ONE_STARTER_NAME, false, true), Prefix.of(engineStarterName, true, false));

  }

  /**
   * The names of the property sources which the webapps modules of VanillaBP add for their
   * defaults: <code>vanillabp-&lt;adapter type&gt;-webapps</code>. The pattern names no adapter
   * type. A fork of Camunda 7 has a copy of this class, and that copy has to skip the source of
   * the webapps module this class was copied from as well. A webapps module which names its
   * source in another way makes its defaults count as keys of the application.
   */
  private static final Pattern ADDED_BY_A_WEBAPPS_MODULE = Pattern.compile("vanillabp-[a-z0-9]+-webapps");

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
   * @param key The key below <code>camunda.bpm.</code>, or below the prefix of the engine's
   *          starter. It also covers every key below it
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
              "job-execution.deployment-aware", "VanillaBP sets it: the job executor always fetches only the jobs "
                  + "of the deployments of your workflow modules, every version of them. "
                  + REMOVE_IT),
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
              "id-generator", "Write 'vanillabp.adapters.%1$s.id-generator' instead: 'uuid' for the starter's "
                  + "default 'strong', which is also VanillaBP's default, and 'db' for 'simple'."),
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
              "client", "Add Camunda's starter camunda-bpm-spring-boot-starter-external-task-client, which reads "
                  + "these keys, or remove the key."),
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
   * The keys which Camunda's starter of the external task client reads, where it is on the
   * classpath.
   */
  private static final List<String> READ_BY_THE_EXTERNAL_TASK_CLIENT = List.of("client");

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
   * Whether the starter of the external task client of the engine's own starter name is on the
   * classpath, the one which reads <code>&lt;name&gt;.bpm.client.*</code>. For Camunda 7 it is
   * the same as {@link #externalTaskClientIsPresent}. A fork which renamed its keys is expected to
   * keep the class of Camunda's starter in its own package. Operaton 2.2 does:
   * <code>org.operaton.bpm.client.spring.boot.starter.ClientProperties</code> binds
   * <code>operaton.bpm.client.*</code>.
   *
   * @param classLoader The class loader to ask
   * @return Whether the starter is there
   */
  static boolean engineExternalTaskClientIsPresent(
      final ClassLoader classLoader) {

    return org.springframework.util.ClassUtils
        .isPresent("org.%s.bpm.client.spring.boot.starter.ClientProperties".formatted(ENGINE_STARTER_NAME),
            classLoader);

  }

  /**
   * Whether Camunda's starter of the external task client is on the classpath.
   *
   * @param classLoader The class loader to ask
   * @return Whether the starter is there
   */
  static boolean externalTaskClientIsPresent(
      final ClassLoader classLoader) {

    return org.springframework.util.ClassUtils.isPresent(EXTERNAL_TASK_CLIENT_CLASS, classLoader);

  }

  /**
   * Whether a property source is one which a webapps module of VanillaBP adds for its
   * defaults. The keys in it were not written by the application, so they are no keys of
   * version 1.
   *
   * @param propertySourceName The name of the property source
   * @return Whether a webapps module of VanillaBP added it
   */
  static boolean isAddedByAWebappsModule(
      final String propertySourceName) {

    return (propertySourceName != null) && ADDED_BY_A_WEBAPPS_MODULE
        .matcher(propertySourceName)
        .matches();

  }

  /**
   * Ends the start if the configuration holds a key of the starter which nobody reads. All
   * such keys are named at once, so one restart is enough to fix them.
   *
   * @param adapterIds The ids of the configured adapters of type <code>camunda7</code>
   * @param propertyNames Every property name the environment knows
   * @param webappsModuleIsPresent Whether the webapps module is on the classpath
   * @param externalTaskClientIsPresent Whether Camunda's starter of the external task client
   *          is on the classpath
   * @throws IllegalStateException Naming every such key and what to write instead
   */
  static void endTheStartOnKeysOfVersionOne(
      final Collection<String> adapterIds,
      final Iterable<String> propertyNames,
      final boolean webappsModuleIsPresent,
      final boolean externalTaskClientIsPresent) {

    endTheStartOnStarterKeys(ENGINE_STARTER_NAME, adapterIds, propertyNames, webappsModuleIsPresent,
        externalTaskClientIsPresent, false);

  }

  /**
   * Ends the start if the configuration holds a key of a starter which nobody reads: a key of
   * version 1, or a key of the engine's own starter where that one has another name. All such
   * keys are named at once, so one restart is enough to fix them.
   *
   * @param engineStarterName The name in the keys of the engine's starter, see
   *          {@link #ENGINE_STARTER_NAME}
   * @param adapterIds The ids of the configured adapters of type <code>camunda7</code>
   * @param propertyNames Every property name the environment knows
   * @param webappsModuleIsPresent Whether the webapps module is on the classpath
   * @param externalTaskClientIsPresent Whether Camunda's starter of the external task client
   *          is on the classpath
   * @param engineExternalTaskClientIsPresent Whether the starter of the external task client
   *          which reads the keys of the engine's starter name is on the classpath. It matters
   *          only where that name is not the one of version 1
   * @throws IllegalStateException Naming every such key and what to write instead
   */
  static void endTheStartOnStarterKeys(
      final String engineStarterName,
      final Collection<String> adapterIds,
      final Iterable<String> propertyNames,
      final boolean webappsModuleIsPresent,
      final boolean externalTaskClientIsPresent,
      final boolean engineExternalTaskClientIsPresent) {

    final var prefixes = prefixesFor(engineStarterName);
    final var findings = new LinkedHashMap<String, String>();
    String keysOfTheEngineOnly = null;
    for (final var name : propertyNames) {
      if ((name == null) || findings.containsKey(name)) {
        continue;
      }
      final var found = keyBelow(prefixes, name);
      if ((found == null) || isLeftAlone(found, webappsModuleIsPresent, externalTaskClientIsPresent,
          engineExternalTaskClientIsPresent)) {
        continue;
      }
      if (found
          .prefix()
          .ofTheEngineOnly()) {
        keysOfTheEngineOnly = found
            .prefix()
            .properties();
      }
      findings.put(name, successorOf(found, prefixes, adapterIds));
    }
    if (findings.isEmpty()) {
      return;
    }
    throw new IllegalStateException(messageFor(findings, keysOfTheEngineOnly, adapterIds));

  }

  private static String messageFor(
      final Map<String, String> findings,
      final String keysOfTheEngineOnly,
      final Collection<String> adapterIds) {

    final var lines = new ArrayList<String>();
    lines.add("The configuration still has keys of Camunda's Spring Boot starter, which VanillaBP 1 used to "
        + "build the engine. VanillaBP 2 builds the Camunda 7 engine itself and does not read them. So they "
        + "would change nothing. Change each of them:");
    if (keysOfTheEngineOnly != null) {
      lines.add("The keys below '%s*' belong to the Spring Boot starter of the Camunda 7 engine. VanillaBP 2 "
          .formatted(keysOfTheEngineOnly)
          + "does not use that starter either.");
    }
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
      final Found found,
      final boolean webappsModuleIsPresent,
      final boolean externalTaskClientIsPresent,
      final boolean engineExternalTaskClientIsPresent) {

    final var key = found.key();
    final var prefix = found.prefix();
    // a starter writes its version keys below its own prefix, whichever starter it is
    if (WRITTEN_BY_THE_STARTER
        .stream()
        .anyMatch(written -> covers(written, key))) {
      return true;
    }
    final var clientIsPresent = prefix.ofVersionOne()
        ? externalTaskClientIsPresent
        : engineExternalTaskClientIsPresent;
    if (clientIsPresent && READ_BY_THE_EXTERNAL_TASK_CLIENT
        .stream()
        .anyMatch(read -> covers(read, key))) {
      return true;
    }
    // the web applications of the engine read the keys of the engine's starter
    return webappsModuleIsPresent && prefix.ofTheEngine() && READ_BY_THE_WEBAPPS_MODULE
        .stream()
        .anyMatch(read -> covers(read, key));

  }

  private static String successorOf(
      final Found found,
      final List<Prefix> prefixes,
      final Collection<String> adapterIds) {

    final var key = found.key();
    // where the engine's starter renamed the keys, two hints depend on the prefix
    if (found
        .prefix()
        .ofVersionOneOnly() && covers("webapp", key)) {
      final var engineProperties = prefixes
          .stream()
          .filter(Prefix::ofTheEngine)
          .findFirst()
          .orElseThrow()
          .properties();
      return "Camunda 7's web applications read '%swebapp.*' instead. Write the key there, with the module "
          .formatted(engineProperties)
          + "camunda7-adapter-spring-boot-webapps on the classpath, or remove it.";
    }
    if (found
        .prefix()
        .ofTheEngineOnly() && covers("client", key)) {
      return "Add the Spring Boot starter of the external task client of Camunda 7, which reads these keys, "
          + "or remove the key.";
    }
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
   * The key below one of the prefixes in its normalized form, or <code>null</code> for a
   * property name which is no such key. The normalized form has no case, no dashes and no
   * underscores within a part. An environment variable cannot be split into parts without
   * guessing. Its normalized form keeps the underscores as separators, and {@link #covers}
   * compares it in the same form.
   */
  private static Found keyBelow(
      final List<Prefix> prefixes,
      final String name) {

    final var normalized = normalized(name);
    for (final var prefix : prefixes) {
      if (name.startsWith(prefix.environment())) {
        return new Found(prefix, "_"
            + name.substring(prefix.environment().length()));
      }
      if (normalized.startsWith(prefix.properties())) {
        return new Found(prefix, normalized.substring(prefix.properties().length()));
      }
    }
    return null;

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
