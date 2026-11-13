package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngines;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the watch around the engine plugins does with a real engine on H2. A plugin which
 * changes a setting the adapter sets itself ends the start, and the message names the
 * setting, the adapter's value, the plugin and the key. With the key the engine starts, and
 * each change gets a WARN. A plugin which changes any other setting is left alone. The same
 * on a booted application is {@code Camunda7PluginChangingAnAdapterSettingIT} (Spring Boot)
 * and {@code Camunda7PluginChangingAnAdapterSettingTest} (Quarkus).
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AdapterSettingsGuardTest {

  private static final String KEY = "vanillabp.adapters.c7.allow-plugins-to-change-adapter-settings";

  /** Switches off the deployment awareness the adapter sets. */
  public static class NotDeploymentAwarePlugin extends AbstractProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration.setJobExecutorDeploymentAware(false);
    }

  }

  /**
   * Replaces the id generator. It has a key of its own (<code>id-generator</code>), so it is
   * free for a plugin, like the history level.
   */
  public static class IdGeneratorPlugin extends AbstractProcessEnginePlugin {

    private final StrongUuidGenerator idGenerator = new StrongUuidGenerator();

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration.setIdGenerator(idGenerator);
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

  /** Adds a parse listener, as many plugins do, next to the adapter's. */
  public static class AddingParseListenerPlugin extends AbstractProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration
          .getCustomPreBPMNParseListeners()
          .add(new AbstractBpmnParseListener() {
          });
    }

  }

  /** Replaces the list of parse listeners, so the adapter's one is gone. */
  public static class ReplacingParseListenersPlugin extends AbstractProcessEnginePlugin {

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration.setCustomPreBPMNParseListeners(new ArrayList<>());
    }

  }

  /** Switches off the deployment awareness after the engine filled its settings. */
  public static class LateDeploymentAwarePlugin extends AbstractProcessEnginePlugin {

    @Override
    public void postInit(
        final ProcessEngineConfigurationImpl configuration) {
      configuration.setJobExecutorDeploymentAware(false);
    }

  }

  /** Switches off the deployment awareness once the engine is built. */
  public static class AfterTheBuildPlugin extends AbstractProcessEnginePlugin {

    @Override
    public void postProcessEngineBuild(
        final ProcessEngine processEngine) {
      processEngine
          .getProcessEngineConfiguration()
          .setJobExecutorDeploymentAware(false);
    }

  }

  /** Named like the class Quarkus generates around a CDI bean. */
  public static class Plain_ClientProxy extends AbstractProcessEnginePlugin {
  }

  private static ProcessEngine build(
      final String database,
      final boolean allowed,
      final ProcessEnginePlugin... plugins) {

    final var configuration = configuration(database, plugins);
    Camunda7AdapterSettingsGuard.watch("c7", configuration, allowed, List.of());
    return configuration.buildProcessEngine();

  }

  private static ProcessEngineConfigurationImpl configuration(
      final String database,
      final ProcessEnginePlugin... plugins) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database));
    configuration.setProcessEngineName(database);
    configuration.setJobExecutorActivate(false);
    configuration.setDbMetricsReporterActivate(false);
    // what the adapter sets on both platforms
    configuration.setIdGenerator(new StrongUuidGenerator());
    configuration.setJobExecutorDeploymentAware(true);
    configuration
        .setCustomPreBPMNParseListeners(new ArrayList<>(List.<BpmnParseListener>of(new AbstractBpmnParseListener() {
        })));
    configuration
        .getProcessEnginePlugins()
        .addAll(List.of(plugins));
    return configuration;

  }

  @Test
  @DisplayName("a plugin which switches off the deployment awareness ends the start, and the message names it")
  public void aChangedSettingEndsTheStart() {

    final var failure = assertThrows(IllegalStateException.class,
        () -> build("guard-deployment-aware", false, new RetriesPlugin(), new NotDeploymentAwarePlugin()));

    final var message = failure.getMessage();
    assertTrue(message.startsWith("Camunda 7 adapter 'c7': an engine plugin of your application changed settings"),
        message);
    assertTrue(message.contains("'jobExecutorDeploymentAware': the adapter set 'true', and the plugin "
        + NotDeploymentAwarePlugin.class.getName()
        + " changed it to 'false' in preInit"), message);
    // the plugin before it changed nothing of the adapter's
    assertFalse(message.contains(RetriesPlugin.class.getName()), message);
    assertTrue(message.contains("set '"
        + KEY
        + ": true'"), message);
    assertNull(ProcessEngines.getProcessEngines().get("guard-deployment-aware"), "no engine is left behind");

  }

  @Test
  @DisplayName("with the key the engine starts with the plugin's value, and the change gets a WARN")
  public void theKeyTurnsTheEndIntoAWarning(
      final CapturedOutput output) {

    final var engine = build("guard-allowed", true, new NotDeploymentAwarePlugin());
    try {
      assertFalse(((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
          .isJobExecutorDeploymentAware());
      final var warning = output
          .getAll()
          .lines()
          .filter(line -> line.contains("changed the setting 'jobExecutorDeploymentAware'"))
          .findFirst()
          .orElse("");
      assertTrue(warning.contains("WARN"), warning);
      assertTrue(warning.contains("Camunda7[c7]: the engine plugin "
          + NotDeploymentAwarePlugin.class.getName()), warning);
      assertTrue(warning.contains("'"
          + KEY
          + "' allows this"), warning);
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("a plugin which changes a setting the adapter leaves alone or the id generator, or adds a parse listener, is fine")
  public void otherSettingsStayFree() {

    final var idGeneratorPlugin = new IdGeneratorPlugin();
    final var engine = build("guard-other", false, new RetriesPlugin(), new AddingParseListenerPlugin(),
        idGeneratorPlugin);
    try {
      final var configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
      assertEquals(5, configuration.getDefaultNumberOfRetries());
      assertEquals(2, configuration.getCustomPreBPMNParseListeners().size());
      assertSame(idGeneratorPlugin.idGenerator, configuration.getIdGenerator());
    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("a plugin which drops the adapter's parse listener ends the start")
  public void aRemovedEntryEndsTheStart() {

    final var failure = assertThrows(IllegalStateException.class,
        () -> build("guard-parse-listeners", false, new ReplacingParseListenersPlugin()));

    final var message = failure.getMessage();
    assertTrue(message.contains("'customPreBPMNParseListeners': the plugin "
        + ReplacingParseListenersPlugin.class.getName()
        + " removed what the adapter put there"), message);

  }

  @Test
  @DisplayName("a change in postInit is found as well, and named with its step")
  public void aChangeInPostInitIsFound() {

    final var failure = assertThrows(IllegalStateException.class,
        () -> build("guard-post-init", false, new LateDeploymentAwarePlugin()));

    assertTrue(failure.getMessage().contains("'jobExecutorDeploymentAware': the adapter set 'true', and the plugin "
        + LateDeploymentAwarePlugin.class.getName()
        + " changed it to 'false' in postInit"), failure.getMessage());

  }

  @Test
  @DisplayName("a change after the engine was built is found, and the engine is closed again")
  public void aChangeAfterTheBuildClosesTheEngine() {

    final var failure = assertThrows(IllegalStateException.class,
        () -> build("guard-after-build", false, new AfterTheBuildPlugin()));

    assertTrue(failure.getMessage().contains("in postProcessEngineBuild"), failure.getMessage());
    assertNull(ProcessEngines.getProcessEngines().get("guard-after-build"), "the engine was closed");

  }

  @Test
  @DisplayName("without a plugin nothing is put around the plugins")
  public void withoutAPluginNothingIsWatched() {

    final var configuration = configuration("guard-none");
    Camunda7AdapterSettingsGuard.watch("c7", configuration, false, List.of());
    assertTrue(configuration.getProcessEnginePlugins().isEmpty());

  }

  @Test
  @DisplayName("a setting only a platform has is watched like the others")
  public void aSettingOfThePlatformIsWatched() {

    final var configuration = configuration("guard-platform", new RetriesPlugin());
    Camunda7AdapterSettingsGuard
        .watch("c7", configuration, false, List
            .of(Camunda7AdapterSettingsGuard.Setting
                .value("defaultNumberOfRetries", ProcessEngineConfigurationImpl::getDefaultNumberOfRetries)));

    final var failure = assertThrows(IllegalStateException.class, configuration::buildProcessEngine);
    assertTrue(failure.getMessage().contains("'defaultNumberOfRetries': the adapter set '3'"), failure.getMessage());
    assertEquals(
        List
            .of(
                "classLoader", "processEngineName", "transactionsExternallyManaged", "customPreBPMNParseListeners",
                "customPostBPMNParseListeners", "expressionManager", "historyEventHandler",
                "customPostCommandInterceptorsTxRequired", "customPostCommandInterceptorsTxRequiresNew", "jobExecutor",
                "jobExecutorActivate", "jobExecutorDeploymentAware", "transactionManager"),
        Camunda7AdapterSettingsGuard
            .namesOf(List.of(Camunda7AdapterSettingsGuard.Setting.value("transactionManager", c -> null))));

  }

  @Test
  @DisplayName("a field which is not there names the class and the field")
  public void aMissingFieldIsNamed() {

    final var failure = assertThrows(IllegalStateException.class,
        () -> Camunda7AdapterSettingsGuard.fieldOf(StandaloneInMemProcessEngineConfiguration.class, "nothing"));
    assertTrue(failure.getMessage().contains("has no field 'nothing'"), failure.getMessage());

    final var idGenerator = Camunda7AdapterSettingsGuard
        .fieldOf(ProcessEngineConfigurationImpl.class, "idGenerator")
        .apply(configuration("guard-field"));
    assertInstanceOf(StrongUuidGenerator.class, idGenerator);

  }

  @Test
  @DisplayName("a plugin is named by the class the developer wrote, and a value by its class or itself")
  public void namesAndValues() {

    assertEquals(Camunda7AdapterSettingsGuardTest.class.getName()
        + "$Plain", Camunda7AdapterSettingsGuard.nameOf(new Plain_ClientProxy()));
    assertEquals("nothing", Camunda7AdapterSettingsGuard.describe(null));
    assertEquals("'true'", Camunda7AdapterSettingsGuard.describe(Boolean.TRUE));
    assertEquals(StrongUuidGenerator.class.getName(), Camunda7AdapterSettingsGuard.describe(new StrongUuidGenerator()));

  }

}
