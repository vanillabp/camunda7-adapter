package io.vanillabp.camunda7.quarkus;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.plugins.IdGeneratorPlugin;
import io.vanillabp.camunda7.quarkus.plugins.RetriesPlugin;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Two engine plugins configured under {@code engine-plugins}. One replaces the id generator
 * the adapter sets, and the key allows it. The other changes a setting the adapter leaves
 * alone. The application starts with both values, and only the first plugin gets a WARN.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7PluginAllowedToChangeAnAdapterSettingTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addClass(IdGeneratorPlugin.class)
          .addClass(RetriesPlugin.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-plugin-allowed;DB_CLOSE_DELAY=-1")
      .overrideConfigKey("vanillabp.adapters.c7.engine-plugins.ids.plugin-class", IdGeneratorPlugin.class.getName())
      .overrideConfigKey("vanillabp.adapters.c7.engine-plugins.retries.plugin-class", RetriesPlugin.class.getName())
      .overrideConfigKey("vanillabp.adapters.c7.allow-plugins-to-change-adapter-settings", "true")
      .setLogRecordPredicate(record -> record
          .getLoggerName()
          .startsWith("io.vanillabp"))
      .assertLogRecords(Camunda7PluginAllowedToChangeAnAdapterSettingTest::onlyTheChangedSettingIsWarnedAbout);

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  private static void onlyTheChangedSettingIsWarnedAbout(
      final List<LogRecord> records) {

    final var warnings = records
        .stream()
        .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
        .map(Camunda7PluginAllowedToChangeAnAdapterSettingTest::textOf)
        .filter(text -> text.contains("which the adapter sets itself"))
        .toList();
    Assertions.assertEquals(1, warnings.size(), warnings::toString);
    final var warning = warnings.getFirst();
    Assertions
        .assertTrue(warning.contains("Camunda7[c7]: the engine plugin "
            + IdGeneratorPlugin.class.getName()
            + " changed the setting 'idGenerator'"), warning);
    Assertions
        .assertTrue(warning.contains("'vanillabp.adapters.c7.allow-plugins-to-change-adapter-settings' allows this"),
            warning);

  }

  private static String textOf(
      final LogRecord record) {

    var text = String.valueOf(record.getMessage());
    final var parameters = record.getParameters();
    if (parameters != null) {
      for (final var parameter : parameters) {
        text = text.replaceFirst("\\{}", Matcher.quoteReplacement(String.valueOf(parameter)));
      }
    }
    return text;

  }

  @Test
  @DisplayName("With the key the engine starts with the values of both plugins")
  public void theEngineHasTheValuesOfBothPlugins() {

    final var configuration = (ProcessEngineConfigurationImpl) engineRegistry
        .engineFor("c7")
        .getProcessEngine()
        .getProcessEngineConfiguration();
    Assertions.assertInstanceOf(IdGeneratorPlugin.PrefixedIdGenerator.class, configuration.getIdGenerator());
    Assertions.assertEquals(5, configuration.getDefaultNumberOfRetries());

  }

}
