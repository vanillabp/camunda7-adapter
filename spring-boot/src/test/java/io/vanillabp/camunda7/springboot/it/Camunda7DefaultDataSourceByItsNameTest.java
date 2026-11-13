package io.vanillabp.camunda7.springboot.it;

import javax.sql.DataSource;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.h2.Driver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * A <code>data-source-name</code> which names the application's default datasource by its
 * bean name, such as <code>dataSource</code>, is no datasource of its own. It is the same
 * instance the value <code>default</code> gives. So the engine joins the caller's
 * transaction and does not deduplicate deliveries, exactly as with <code>default</code>.
 * Each case builds databases of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DefaultDataSourceByItsNameTest {

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

  private static DataSource h2(
      final String name) {

    return new SimpleDriverDataSource(new Driver(), "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));

  }

  /**
   * What decides how the engine treats the caller's transaction, read from a started
   * context.
   */
  private record EngineFacts(
                             boolean usesTheApplicationsDataSource,
                             boolean usesTheApplicationsTransactionManager,
                             boolean ownDataSource,
                             boolean taskRegistryKnowsOwnDataSource,
                             boolean deliversTasksAtLeastOnce) {
  }

  private static EngineFacts engineFacts(
      final AssertableApplicationContext context) {

    Assertions.assertNull(context.getStartupFailure(), "context should start");
    final var holder = context.getBean(Camunda7EngineHolder.class);
    final var configuration = (SpringProcessEngineConfiguration) ((ProcessEngineConfigurationImpl) holder
        .getProcessEngine()
        .getProcessEngineConfiguration());
    final var dataSource = configuration.getDataSource() instanceof TransactionAwareDataSourceProxy proxy
        ? proxy.getTargetDataSource()
        : configuration.getDataSource();
    return new EngineFacts(
        dataSource == context.getBean(DataSource.class), configuration.getTransactionManager() == context
            .getBean(PlatformTransactionManager.class), holder.usesSeparateDataSource(), holder
                .getTaskRegistry()
                .engineRunsOnItsOwnDataSource(), context
                    .getBean(Camunda7ProcessService.class)
                    .deliversTasksAtLeastOnce());

  }

  private EngineFacts factsFor(
      final String dataSourceName,
      final String databaseName) {

    final var facts = new EngineFacts[1];
    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(databaseName),
            "vanillabp.adapters.c7.data-source-name=%s".formatted(dataSourceName))
        .run(context -> facts[0] = engineFacts(context));
    return facts[0];

  }

  @Test
  @DisplayName("Naming Spring Boot's datasource 'dataSource' behaves like 'default'")
  public void theBeanNameOfTheDefaultDataSourceBehavesLikeDefault(
      final CapturedOutput output) {

    final var byDefault = factsFor("default", "by-name-default");
    final var byName = factsFor("dataSource", "by-name-data-source");

    Assertions.assertEquals(new EngineFacts(true, true, false, false, false), byDefault);
    Assertions.assertEquals(byDefault, byName);
    Assertions.assertTrue(
        output
            .getAllOfThisTest()
            .contains("Camunda7[c7]: the datasource bean 'dataSource' ('vanillabp.adapters.c7.data-source-name') is "
                + "the application's default datasource. The engine runs on it as with the value 'default'"),
        output::getAllOfThisTest);

  }

  @Test
  @DisplayName("Naming the primary one of several datasources behaves like the application's datasource")
  public void theBeanNameOfThePrimaryDataSourceIsShared() {

    final var application = h2("by-name-primary-app");
    this.contextRunner
        .withBean("ordersDataSource", DataSource.class, () -> application, definition -> definition.setPrimary(true))
        .withBean("archiveDataSource", DataSource.class, () -> h2("by-name-primary-archive"))
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "vanillabp.adapters.c7.data-source-name=ordersDataSource")
        .run(context -> Assertions.assertEquals(new EngineFacts(true, true, false, false, false), engineFacts(
            context)));

  }

  @Test
  @DisplayName("Naming a datasource which is not the default one is still a datasource of its own")
  public void anotherDataSourceIsStillOwn() {

    final var engine = h2("by-name-other-engine");
    this.contextRunner
        .withBean("ordersDataSource", DataSource.class, () -> h2("by-name-other-app"), definition -> definition
            .setPrimary(true))
        .withBean("engineDataSource", DataSource.class, () -> engine)
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "vanillabp.adapters.c7.data-source-name=engineDataSource")
        .run(context -> {

          final var facts = engineFacts(context);
          Assertions.assertFalse(facts.usesTheApplicationsTransactionManager());
          Assertions.assertTrue(facts.ownDataSource());
          Assertions.assertTrue(facts.taskRegistryKnowsOwnDataSource());
          Assertions.assertTrue(facts.deliversTasksAtLeastOnce());

        });

  }

}
