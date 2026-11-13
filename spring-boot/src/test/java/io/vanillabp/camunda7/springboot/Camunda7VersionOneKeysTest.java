package io.vanillabp.camunda7.springboot;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * A key of Camunda's Spring Boot starter, <code>camunda.bpm.*</code>, ends the start and
 * names what to write instead. The webapps module is not on this module's classpath. So
 * here even <code>camunda.bpm.webapp.*</code> ends the start. The webapps module has a test
 * of its own which shows that it does not there.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7VersionOneKeysTest {

  private static final String INTRODUCTION = "The configuration still has keys of Camunda's Spring Boot starter";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(
          AutoConfigurations.of(
              org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
              org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
              Camunda7AdapterConfiguration.class,
              Camunda7ProcessServiceConfiguration.class,
              WorkflowModuleAutoConfiguration.class,
              SpringBootMigrationAdapterAutoConfiguration.class));

  private static String messageFor(
      final List<String> adapterIds,
      final boolean webappsModuleIsPresent,
      final String... propertyNames) {

    // the starter name of Camunda 7 is written out, so that a fork which renamed its keys runs
    // these cases unchanged. The cases of such a fork are below, with 'operaton'
    return messageFor("camunda", adapterIds, webappsModuleIsPresent, false, propertyNames);

  }

  private static String messageFor(
      final String engineStarterName,
      final List<String> adapterIds,
      final boolean webappsModuleIsPresent,
      final boolean engineExternalTaskClientIsPresent,
      final String... propertyNames) {

    try {
      // Camunda7ExternalTaskClientVersionOneKeysTest holds the keys of Camunda's external task client
      Camunda7VersionOneKeys.endTheStartOnStarterKeys(engineStarterName, adapterIds, List.of(propertyNames),
          webappsModuleIsPresent, false, engineExternalTaskClientIsPresent);
      return null;
    } catch (final IllegalStateException e) {
      return e.getMessage();
    }

  }

  @Test
  @DisplayName("A key of the starter ends the start of an application and names the key of version 2")
  public void aKeyOfTheStarterEndsTheStart() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-v1-keys;DB_CLOSE_DELAY=-1",
            "camunda.bpm.history-level=audit",
            "camunda.bpm.database.schema-update=false")
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "the start has to end");
          var cause = context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = cause.getMessage();
          Assertions.assertTrue(message.startsWith(INTRODUCTION), message);
          Assertions.assertTrue(
              message.contains("  - 'camunda.bpm.history-level': Write 'vanillabp.adapters.c7.history-level' instead."),
              message);
          Assertions.assertTrue(
              message.contains("  - 'camunda.bpm.database.schema-update': Write "
                  + "'vanillabp.adapters.c7.database-schema-update' instead."),
              message);

        });

  }

  @Test
  @DisplayName("Every key is named at once, each with what became of it")
  public void everyKeyIsNamedAtOnce() {

    final var message = messageFor(
        List.of("c7"),
        false,
        "server.port",
        "camunda.bpm.job-execution.core-pool-size",
        "camunda.bpm.job-execution.deployment-aware",
        "camunda.bpm.id-generator",
        "camunda.bpm.generic-properties.properties.enable-exceptions-after-unhandled-bpmn-error",
        "camunda.bpm.authorization.enabled",
        "camunda.bpm.default-number-of-retries",
        "camunda.bpm.eventing.task",
        "camunda.bpm.admin-user.id",
        "camunda.bpm.database.type",
        "camunda.bpm.database.schema-name",
        "camunda.bpm.deployment-resource-pattern[0]",
        "camunda.bpm.process-engine-name",
        "camunda.bpm.webapp.application-path",
        "camunda.bpm.enabled",
        "camunda.bpm.something-nobody-knows");

    Assertions.assertNotNull(message);
    Assertions.assertFalse(message.contains("server.port"), message);
    final var noKeyYet = "Version 2 has no key for this yet. Set the value in a ProcessEnginePlugin bean of your "
        + "application.";
    Assertions.assertTrue(message.contains("'camunda.bpm.job-execution.core-pool-size': "
        + noKeyYet), message);
    // the adapter sets this one itself, so it needs no key
    Assertions.assertTrue(message.contains("'camunda.bpm.job-execution.deployment-aware': VanillaBP sets it"), message);
    Assertions.assertTrue(message.contains("'camunda.bpm.id-generator': Write 'vanillabp.adapters.c7.id-generator' "
        + "instead: 'uuid' for the starter's default 'strong', which is also VanillaBP's default, and 'db' for "
        + "'simple'."), message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.generic-properties.properties.enable-exceptions-after-unhandled-bpmn-error': "
            + noKeyYet),
        message);
    Assertions.assertTrue(message.contains("'camunda.bpm.authorization.enabled': "
        + noKeyYet), message);
    Assertions.assertTrue(message.contains("'camunda.bpm.default-number-of-retries': "
        + noKeyYet), message);
    Assertions.assertTrue(message.contains("'camunda.bpm.database.schema-name': "
        + noKeyYet), message);
    Assertions.assertTrue(
        message
            .contains("'camunda.bpm.eventing.task': The engine publishes no Spring events in version 2, on purpose."),
        message);
    Assertions.assertTrue(message.contains("@TaskEvent"), message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.admin-user.id': Write it below 'vanillabp.adapters.c7.webapps.admin-user' "
            + "instead"),
        message);
    // the most specific rule wins over the group around it
    Assertions.assertTrue(
        message.contains("'camunda.bpm.database.type': The engine finds the type of the database itself."),
        message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.deployment-resource-pattern[0]': VanillaBP deploys the models of each "
            + "workflow module itself. Name where they are with "
            + "'vanillabp.workflow-modules.<module>.adapters.c7.resources-location' instead."),
        message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.process-engine-name': VanillaBP names the engine 'vanillabp-camunda7-c7'."),
        message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.webapp.application-path': Add the module "
            + "camunda7-adapter-spring-boot-webapps"),
        message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.enabled': VanillaBP builds an engine for every adapter id of type camunda7."),
        message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.something-nobody-knows': Nothing in VanillaBP 2 reads this key. Remove it."),
        message);
    Assertions.assertFalse(message.contains("Where a key names '<id>'"), message);

  }

  @Test
  @DisplayName("With the webapps module, the keys it reads and the keys its starter writes are left alone")
  public void theKeysOfTheWebappsModuleAreLeftAlone() {

    Assertions.assertNull(
        messageFor(
            List.of("c7"),
            true,
            "camunda.bpm.webapp.application-path",
            "camunda.bpm.webapp.csrf.target-origin",
            "camunda.bpm.enabled",
            "CAMUNDA_BPM_WEBAPP_INDEX_REDIRECT_ENABLED",
            "camunda.bpm.version",
            "camunda.bpm.formatted-version",
            "camunda.bpm.is-enterprise"));
    // the starter writes its version keys whether the module is there or not
    Assertions.assertNull(messageFor(List.of("c7"), false, "camunda.bpm.version"));
    final var message = messageFor(List.of("c7"), true, "camunda.bpm.webapp.application-path",
        "camunda.bpm.history-level");
    Assertions.assertNotNull(message);
    Assertions.assertFalse(message.contains("Add the module camunda7-adapter-spring-boot-webapps"), message);
    Assertions.assertTrue(message.contains("'camunda.bpm.history-level': Write"), message);

  }

  @Test
  @DisplayName("Keys are found in every spelling Spring Boot binds, environment variables included")
  public void everySpellingIsFound() {

    final var message = messageFor(
        List.of("c7"),
        false,
        "camunda.bpm.historyLevel",
        "camunda.bpm.database.schema_update",
        "CAMUNDA_BPM_HISTORY_LEVEL_DEFAULT",
        "CAMUNDA_BPM_JOB_EXECUTION_MAX_WAIT",
        "CAMUNDA_BPM_ENABLED");

    Assertions.assertNotNull(message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.historyLevel': Write 'vanillabp.adapters.c7.history-level' instead."),
        message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.database.schema_update': Write "
            + "'vanillabp.adapters.c7.database-schema-update' instead."),
        message);
    Assertions.assertTrue(
        message.contains("'CAMUNDA_BPM_HISTORY_LEVEL_DEFAULT': Write 'vanillabp.adapters.c7.history-level' instead."),
        message);
    Assertions.assertTrue(message.contains("'CAMUNDA_BPM_JOB_EXECUTION_MAX_WAIT': Version 2 has no key"), message);
    Assertions.assertTrue(message.contains("'CAMUNDA_BPM_ENABLED': VanillaBP builds an engine"), message);

  }

  @Test
  @DisplayName("With several adapter ids, the message names them and keeps the placeholder")
  public void severalAdapterIdsKeepThePlaceholder() {

    final var message = messageFor(List.of("c7", "c7-legacy"), false, "camunda.bpm.history-level");

    Assertions.assertNotNull(message);
    Assertions.assertTrue(
        message.contains("'camunda.bpm.history-level': Write 'vanillabp.adapters.<id>.history-level' instead."),
        message);
    Assertions.assertTrue(
        message.contains("Where a key names '<id>', write it once for each Camunda 7 adapter id that needs it "
            + "(c7, c7-legacy)."),
        message);

  }

  @Test
  @DisplayName("An engine whose starter reads the keys of version 1 checks these keys only")
  public void anEngineWithTheKeysOfVersionOneHasOnePrefix() {

    final var message = messageFor(List.of("c7"), false, "camunda.bpm.history-level", "operaton.bpm.history-level");
    Assertions.assertNotNull(message);
    Assertions.assertFalse(message.contains("operaton"), message);
    Assertions.assertFalse(message.contains("belong to the Spring Boot starter of the"), message);

  }

  @Test
  @DisplayName("A fork whose starter renamed its keys: those keys end the start as well, with the same hints")
  public void theRenamedKeysOfAForkEndTheStart() {

    final var message = messageFor("operaton", List.of("c7"), false, false, "camunda.bpm.history-level",
        "operaton.bpm.history-level", "operaton.bpm.database.schema-update", "OPERATON_BPM_JOB_EXECUTION_MAX_WAIT",
        "operaton.bpm.client.base-url", "operaton.bpm.webapp.application-path", "operaton.bpm.something-nobody-knows",
        "operaton.bpm.version");

    Assertions.assertNotNull(message);
    Assertions.assertTrue(message.startsWith(INTRODUCTION), message);
    Assertions.assertTrue(message.contains("The keys below 'operaton.bpm.*' belong to the Spring Boot starter of the "
        + "Camunda 7 engine. VanillaBP 2 does not use that starter either."), message);
    Assertions.assertTrue(
        message.contains("  - 'camunda.bpm.history-level': Write 'vanillabp.adapters.c7.history-level' instead."),
        message);
    Assertions.assertTrue(
        message.contains("  - 'operaton.bpm.history-level': Write 'vanillabp.adapters.c7.history-level' instead."),
        message);
    Assertions.assertTrue(message.contains("  - 'operaton.bpm.database.schema-update': Write "
        + "'vanillabp.adapters.c7.database-schema-update' instead."), message);
    Assertions.assertTrue(message.contains("  - 'OPERATON_BPM_JOB_EXECUTION_MAX_WAIT': Version 2 has no key"),
        message);
    Assertions.assertTrue(message.contains("  - 'operaton.bpm.client.base-url': Add the Spring Boot starter of the "
        + "external task client of Camunda 7"), message);
    Assertions.assertTrue(message.contains("  - 'operaton.bpm.webapp.application-path': Add the module "
        + "camunda7-adapter-spring-boot-webapps"), message);
    Assertions.assertTrue(message.contains("  - 'operaton.bpm.something-nobody-knows': Nothing in VanillaBP 2 reads "
        + "this key."), message);
    // the starter writes its version keys below its own prefix
    Assertions.assertFalse(message.contains("operaton.bpm.version"), message);

  }

  @Test
  @DisplayName("A fork whose starter renamed its keys: its web applications read the renamed keys only")
  public void theWebappsOfAForkReadTheRenamedKeysOnly() {

    Assertions.assertNull(messageFor("operaton", List.of("c7"), true, true, "operaton.bpm.webapp.application-path",
        "OPERATON_BPM_WEBAPP_INDEX_REDIRECT_ENABLED", "operaton.bpm.enabled", "operaton.bpm.client.base-url",
        "camunda.bpm.version"));

    final var message = messageFor("operaton", List.of("c7"), true, true, "camunda.bpm.webapp.application-path",
        "camunda.bpm.enabled");
    Assertions.assertNotNull(message);
    Assertions.assertTrue(message.contains("  - 'camunda.bpm.webapp.application-path': Camunda 7's web "
        + "applications read 'operaton.bpm.webapp.*' instead. Write the key there, with the module "
        + "camunda7-adapter-spring-boot-webapps on the classpath, or remove it."), message);
    Assertions.assertTrue(message.contains("  - 'camunda.bpm.enabled': VanillaBP builds an engine"), message);
    Assertions.assertFalse(message.contains("belong to the Spring Boot starter of the"), message);

  }

  /**
   * A webapps module of VanillaBP adds its default <code>camunda.bpm.enabled</code> in a property
   * source of its own. Here the module is not on the classpath, like the webapps module of the
   * old adapter is unknown to the adapter of a fork of Camunda 7 in the same application. Only
   * the name of the source tells the check that nobody wrote the key.
   */
  private static MapPropertySource defaultOfAWebappsModule() {

    return new MapPropertySource("vanillabp-camunda7-webapps", Map.of("camunda.bpm.enabled", "false"));

  }

  @Test
  @DisplayName("A default which a webapps module adds is no key of the application, also without the module")
  public void aDefaultOfAWebappsModuleDoesNotEndTheStart() {

    this.contextRunner
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .addLast(defaultOfAWebappsModule()))
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-v1-keys-webapps-default;DB_CLOSE_DELAY=-1")
        .run(context -> Assertions.assertNull(context.getStartupFailure(), () -> "the start must not end: "
            + context.getStartupFailure()));

  }

  @Test
  @DisplayName("The same key written by the application still ends the start next to a webapps module's default")
  public void theSameKeyWrittenByTheApplicationEndsTheStart() {

    this.contextRunner
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .addLast(defaultOfAWebappsModule()))
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-v1-keys-user-enabled;DB_CLOSE_DELAY=-1",
            "camunda.bpm.enabled=false")
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "the start has to end");
          var cause = context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = cause.getMessage();
          Assertions.assertTrue(message.startsWith(INTRODUCTION), message);
          Assertions.assertTrue(message.contains("  - 'camunda.bpm.enabled': VanillaBP builds an engine"), message);

        });

  }

  @Test
  @DisplayName("Only a source named like the one of a webapps module counts as added by VanillaBP")
  public void onlyTheSourceOfAWebappsModuleIsSkipped() {

    Assertions.assertTrue(Camunda7VersionOneKeys.isAddedByAWebappsModule("vanillabp-camunda7-webapps"));
    Assertions.assertTrue(Camunda7VersionOneKeys.isAddedByAWebappsModule("vanillabp-cibseven-webapps"));
    Assertions.assertTrue(Camunda7VersionOneKeys.isAddedByAWebappsModule("vanillabp-operaton-webapps"));
    Assertions.assertFalse(Camunda7VersionOneKeys.isAddedByAWebappsModule("vanillabp-webapps"));
    Assertions.assertFalse(Camunda7VersionOneKeys.isAddedByAWebappsModule("my-vanillabp-camunda7-webapps"));
    Assertions.assertFalse(Camunda7VersionOneKeys.isAddedByAWebappsModule(
        "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'"));
    Assertions.assertFalse(Camunda7VersionOneKeys.isAddedByAWebappsModule(null));

  }

  @Test
  @DisplayName("The webapps module is looked for by the name of its class")
  public void theWebappsModuleIsLookedFor() {

    Assertions.assertFalse(Camunda7VersionOneKeys.webappsModuleIsPresent(getClass().getClassLoader()));

  }

}
