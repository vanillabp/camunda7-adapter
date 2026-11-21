package io.vanillabp.camunda7.springboot;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.camunda7.wiring.SuspendedProcessDefinitions;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * The emergency exit for suspended process definitions on Spring Boot. The platform ends the
 * start for a key below <code>vanillabp</code> which no {@code @ConfigurationProperties} class
 * binds. So the overlay binds the key, and the switch set as a system property or as an
 * environment variable lets the application start. Set anywhere else, for example in
 * application.yaml, it would be bound and still do nothing. So that ends the start, with a
 * message which says how to set it.
 * <p>
 * What the switch does to the startup check is
 * <code>Camunda7OldProcessVersionsIT#theEmergencyExitTakesTheSuspendedVersionOut</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EmergencyExitForSuspendedDefinitionsTest {

  private static ApplicationContextRunner application(
      final String database,
      final String... properties) {

    final var all = new String[properties.length + 2];
    all[0] = "spring.config.location=classpath:empty-adapter-section/application.yaml";
    all[1] = "spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database);
    System.arraycopy(properties, 0, all, 2, properties.length);
    return new ApplicationContextRunner()
        .withPropertyValues(all)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class));

  }

  @AfterEach
  public void takeTheSwitchOutAgain() {

    System.clearProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY);

  }

  @Test
  @DisplayName("The switch set as a system property does not end the start")
  public void aSystemPropertyStarts() {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "true");
    application("camunda7-exit-as-system-property").run(context -> {

      Assertions.assertNull(context.getStartupFailure(), "context should start");
      Assertions.assertEquals(
          "true",
          context
              .getBean(VanillaBpCamunda7Properties.class)
              .getIgnoreSuspendedProcessDefinitions(),
          "the overlay binds the key, so the platform counts it as read");

    });

  }

  @Test
  @DisplayName("The switch set as an environment variable does not end the start")
  public void anEnvironmentVariableStarts() {

    application("camunda7-exit-as-environment-variable")
        .withInitializer(context -> context
            .getEnvironment()
            .getPropertySources()
            .replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map
                        .of(SuspendedProcessDefinitions.IGNORE_ENVIRONMENT_VARIABLE, "true"))))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          Assertions.assertEquals(
              "true",
              context
                  .getBean(VanillaBpCamunda7Properties.class)
                  .getIgnoreSuspendedProcessDefinitions());

        });

  }

  @Test
  @DisplayName("The switch set as configuration ends the start and says how to set it")
  public void configurationEndsTheStart() {

    application(
        "camunda7-exit-as-configuration",
        SuspendedProcessDefinitions.IGNORE_PROPERTY
            + "=true")
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "the start should end");
          var cause = (Throwable) context.getStartupFailure();
          final var messages = new StringBuilder();
          while (cause != null) {
            messages
                .append(cause.getMessage())
                .append('\n');
            cause = cause.getCause();
          }
          final var message = messages.toString();
          Assertions.assertTrue(
              message.contains("'%s' is written in a place where it does nothing"
                  .formatted(SuspendedProcessDefinitions.IGNORE_PROPERTY)),
              message);
          Assertions.assertTrue(message.contains("-D%s=true".formatted(SuspendedProcessDefinitions.IGNORE_PROPERTY)),
              message);
          Assertions.assertTrue(message.contains(SuspendedProcessDefinitions.IGNORE_ENVIRONMENT_VARIABLE), message);

        });

  }

  @Test
  @DisplayName("A system property and an environment variable are no configuration")
  public void onlyOtherSourcesCount() {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "true");
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map
                    .of(SuspendedProcessDefinitions.IGNORE_ENVIRONMENT_VARIABLE, "true")));

    Assertions.assertTrue(Camunda7AdapterBeanRegistrar
        .whereTheSwitchIsSetAsConfiguration(environment)
        .isEmpty());

  }

}
