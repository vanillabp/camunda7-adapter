package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.camunda7.engine.Camunda7StartupExplanation;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An engine plugin of the application which changes a setting the adapter sets itself, on a
 * booted Spring Boot application. The start ends and names the setting, the adapter's value,
 * the plugin and the key. With the key the application starts, and the change gets a WARN. A
 * plugin which changes any other setting is left alone. The message which ends the start is
 * the adapter's explanation, so Spring Boot shows it first. The rules themselves are tested in
 * {@code Camunda7AdapterSettingsGuardTest} (core).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7PluginChangingAnAdapterSettingIT {

  private static final String KEY = "vanillabp.adapters.c7.allow-plugins-to-change-adapter-settings";

  /** Switches off the deployment awareness the adapter sets. */
  public static class NotDeploymentAwarePlugin extends AbstractProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration.setJobExecutorDeploymentAware(false);
    }

  }

  /** Changes a setting the adapter leaves alone. */
  public static class RetriesPlugin extends AbstractProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration.setDefaultNumberOfRetries(5);
    }

  }

  private static ProcessEngineConfigurationImpl start(
      final String database,
      final String pluginClass,
      final String... more) {

    final var arguments = new ArrayList<>(List
        .of(
            "--spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database),
            "--vanillabp.adapters.c7.engine-plugins.test.plugin-class=%s".formatted(pluginClass)));
    arguments.addAll(List.of(more));
    try (var application = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(arguments.toArray(String[]::new))) {
      return (ProcessEngineConfigurationImpl) application
          .getBean(ProcessEngine.class)
          .getProcessEngineConfiguration();
    }

  }

  @Test
  @DisplayName("a plugin which switches off the deployment awareness ends the start, and the message names it")
  public void aChangedSettingEndsTheStart() {

    final var failure = assertThrows(RuntimeException.class,
        () -> start("c7-plugin-changes-setting", NotDeploymentAwarePlugin.class.getName()));

    // the failure analyzer shows the message first only if it is the adapter's explanation
    assertNotNull(Camunda7StartupExplanation.in(failure));
    final var message = rootMessage(failure);
    assertTrue(message.contains("Camunda 7 adapter 'c7': an engine plugin of your application changed settings"),
        message);
    assertTrue(message.contains("'jobExecutorDeploymentAware': the adapter set 'true', and the plugin "
        + NotDeploymentAwarePlugin.class.getName()
        + " changed it to 'false' in preInit"), message);
    assertTrue(message.contains("set '"
        + KEY
        + ": true'"), message);

  }

  @Test
  @DisplayName("with the key the application starts with the plugin's value, and the change gets a WARN")
  public void theKeyTurnsTheEndIntoAWarning(
      final CapturedOutput output) {

    final var alreadyLogged = output
        .getAll()
        .length();

    final var configuration = start("c7-plugin-allowed", NotDeploymentAwarePlugin.class.getName(), "--"
        + KEY
        + "=true");

    assertFalse(configuration.isJobExecutorDeploymentAware());
    final var warning = output
        .getAll()
        .substring(alreadyLogged)
        .lines()
        .filter(line -> line.contains("changed the setting 'jobExecutorDeploymentAware'"))
        .findFirst()
        .orElse("");
    assertTrue(warning.contains("WARN"), warning);
    assertTrue(warning.contains(NotDeploymentAwarePlugin.class.getName()), warning);
    assertTrue(warning.contains("'"
        + KEY
        + "' allows this"), warning);

  }

  @Test
  @DisplayName("a plugin which changes a setting the adapter leaves alone is fine")
  public void otherSettingsStayFree() {

    final var configuration = start("c7-plugin-other-setting", RetriesPlugin.class.getName());

    assertEquals(5, configuration.getDefaultNumberOfRetries());

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
