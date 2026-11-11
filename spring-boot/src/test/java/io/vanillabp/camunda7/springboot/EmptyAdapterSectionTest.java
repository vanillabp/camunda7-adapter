package io.vanillabp.camunda7.springboot;

import java.io.IOException;
import java.util.Set;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.config.EmptySections;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * An adapter section written as <code>{}</code> in a YAML file keeps the keys which other
 * property sources set below it. Spring Boot reads the empty map as an empty value at the
 * section's own name, and that value used to hide every key of the id. The platform's
 * {@link EmptySections} prevents this. These tests check that the adapter's own bindings use
 * it: the registrar's binding of the engine keys and the overlay bound as
 * {@code @ConfigurationProperties}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EmptyAdapterSectionTest {

  private static final String ID = "camunda7";

  private static final String SECTIONS = "empty-adapter-section/sections.yaml";

  private static final String NOTHING_BUT_EMPTY_SECTIONS = "empty-adapter-section/nothing-but-empty.yaml";

  /** Loads both documents of the YAML file and puts the command line above them. */
  private static StandardEnvironment environment(
      final String... commandLine) throws IOException {

    return environmentOf(SECTIONS, commandLine);

  }

  private static StandardEnvironment environmentOf(
      final String yamlFile,
      final String... commandLine) throws IOException {

    final var environment = new StandardEnvironment();
    addSources(environment, yamlFile, commandLine);
    return environment;

  }

  private static void addSources(
      final ConfigurableEnvironment environment,
      final String yamlFile,
      final String... commandLine) throws IOException {

    // a later document of a YAML file wins over an earlier one, as in Spring Boot
    for (final var document : new YamlPropertySourceLoader().load(yamlFile, new ClassPathResource(yamlFile))) {
      environment
          .getPropertySources()
          .addFirst(document);
    }
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource(commandLine));

  }

  /**
   * A context which binds the overlay as {@code @ConfigurationProperties}, with the advisor bean
   * of the platform's auto-configuration. The whole auto-configuration would need an adapter
   * bean to start. {@link #theEngineRunsWithTheKeys()} shows that a booted application has this
   * advisor and no other.
   */
  private static ApplicationContextRunner overlayContext(
      final String yamlFile,
      final String... commandLine) {

    return new ApplicationContextRunner()
        .withInitializer(context -> {
          try {
            addSources(context.getEnvironment(), yamlFile, commandLine);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        })
        .withBean(
            "vanillaBpEmptySectionsKeepTheirKeys",
            ConfigurationPropertiesBindHandlerAdvisor.class,
            SpringBootMigrationAdapterAutoConfiguration::vanillaBpEmptySectionsKeepTheirKeys)
        .withUserConfiguration(OverlayOnly.class);

  }

  @EnableConfigurationProperties(VanillaBpCamunda7Properties.class)
  static class OverlayOnly {

  }

  @Test
  @DisplayName("Spring Boot reads an empty YAML section as an empty value at the section's name")
  public void anEmptySectionIsAnEmptyValue() throws IOException {

    final var firstDocument = (EnumerablePropertySource<?>) new YamlPropertySourceLoader()
        .load(SECTIONS, new ClassPathResource(SECTIONS))
        .getFirst();
    // the cause. If Spring Boot stops doing this, the platform's EmptySections is not needed any more
    Assertions.assertEquals("", String.valueOf(firstDocument.getProperty("vanillabp.adapters.camunda7")));

    // and the plain binding which the adapter used to do loses the command line
    final var plain = Binder
        .get(environment("--vanillabp.adapters.camunda7.tenant-id=from-the-command-line"))
        .bind("vanillabp.adapters.%s".formatted(ID), Camunda7EngineProperties.class)
        .orElseGet(Camunda7EngineProperties::new);
    Assertions.assertNull(plain.getTenantId());

  }

  @Test
  @DisplayName("The engine keys of an empty section come from the command line and a second YAML document")
  public void theEngineKeysAreKept() throws IOException {

    final var engineProperties = Camunda7AdapterBeanRegistrar.enginePropertiesFor(
        environment("--vanillabp.adapters.camunda7.tenant-id=from-the-command-line"),
        ID);

    Assertions.assertEquals("from-the-command-line", engineProperties.getTenantId());
    Assertions.assertEquals("P2D", engineProperties.getHistoryTimeToLive());

  }

  @Test
  @DisplayName("The overlay keeps the keys of an empty adapter section and of an empty module section")
  public void theOverlayKeepsTheKeys() {

    overlayContext(
        SECTIONS,
        "--vanillabp.adapters.camunda7.allow-listeners=true",
        "--vanillabp.workflow-modules.loans.adapters.camunda7.tenant-id=loans-tenant")
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          final var overlay = context.getBean(VanillaBpCamunda7Properties.class);
          Assertions.assertTrue(
              overlay
                  .enginePropertiesFor(ID)
                  .isAllowListeners());
          Assertions.assertEquals(
              "P2D",
              overlay
                  .enginePropertiesFor(ID)
                  .getHistoryTimeToLive());
          Assertions.assertEquals(
              "loans-tenant",
              overlay
                  .configuredTenantFor(ID, "loans")
                  .tenantId());

        });

  }

  @Test
  @DisplayName("A section of nothing but {} still configures the id, with the default engine keys")
  public void aSectionOfNothingButEmptyBraces() throws IOException {

    final var engineProperties = Camunda7AdapterBeanRegistrar.enginePropertiesFor(
        environmentOf(NOTHING_BUT_EMPTY_SECTIONS),
        ID);
    Assertions.assertEquals(
        new Camunda7EngineProperties().getHistoryTimeToLive(),
        engineProperties.getHistoryTimeToLive());
    Assertions.assertNull(engineProperties.getTenantId());

    overlayContext(NOTHING_BUT_EMPTY_SECTIONS).run(context -> {

      Assertions.assertNull(context.getStartupFailure(), "context should start");
      final var overlay = context.getBean(VanillaBpCamunda7Properties.class);
      Assertions.assertTrue(
          overlay
              .getAdapters()
              .containsKey(ID),
          "the id of a section of nothing but {} should be bound");
      Assertions.assertFalse(
          overlay
              .enginePropertiesFor(ID)
              .isAllowListeners());

    });

  }

  @Test
  @DisplayName("A booted application runs the engine with the keys set beside an empty section")
  public void theEngineRunsWithTheKeys() {

    new ApplicationContextRunner()
        .withPropertyValues(
            "spring.config.location=classpath:empty-adapter-section/application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-empty-section;DB_CLOSE_DELAY=-1",
            "vanillabp.adapters.camunda7.history-time-to-live=P1D",
            "vanillabp.adapters.camunda7.db-metrics-reporting=true")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          // the platform's advisor keeps the keys of empty sections, the adapter needs none of its own
          Assertions.assertEquals(
              Set.of("vanillaBpEmptySectionsKeepTheirKeys"),
              context
                  .getBeansOfType(ConfigurationPropertiesBindHandlerAdvisor.class)
                  .keySet());
          final var configuration = (ProcessEngineConfigurationImpl) context
              .getBean(Camunda7EngineHolder.class)
              .getProcessEngine()
              .getProcessEngineConfiguration();
          Assertions.assertEquals("P1D", configuration.getHistoryTimeToLive());
          Assertions.assertEquals(
              Boolean.TRUE,
              context
                  .getBean(VanillaBpCamunda7Properties.class)
                  .enginePropertiesFor(ID)
                  .getDbMetricsReporting());

        });

  }

}
