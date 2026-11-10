package io.vanillabp.camunda7.springboot.it;

import javax.sql.DataSource;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.h2.Driver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * A bean <code>camundaBpmDataSource</code> and a bean <code>camundaBpmTransactionManager</code>
 * work as they did in VanillaBP 1, where the adapter id names no datasource. Without them the
 * engine would run on the application's datasource and create empty tables there. The
 * running workflows of the application would seem to be gone, and nothing would say so.
 * <p>
 * The engine's datasource is read from the engine configuration, where Spring's proxy wraps
 * it. Each case builds its own H2 databases, so no case sees the tables of another one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7VersionOneDataSourceTest {

  private static final String CHOSEN = "the engine runs on the DataSource bean 'camundaBpmDataSource', as in "
      + "VanillaBP 1";

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

  private static SpringProcessEngineConfiguration engineConfiguration(
      final AssertableApplicationContext context) {

    Assertions.assertNull(context.getStartupFailure(), "context should start");
    return (SpringProcessEngineConfiguration) ((ProcessEngineConfigurationImpl) context
        .getBean(Camunda7EngineHolder.class)
        .getProcessEngine()
        .getProcessEngineConfiguration());

  }

  private static DataSource engineDataSource(
      final AssertableApplicationContext context) {

    final var dataSource = engineConfiguration(context).getDataSource();
    return dataSource instanceof TransactionAwareDataSourceProxy proxy
        ? proxy.getTargetDataSource()
        : dataSource;

  }

  @Test
  @DisplayName("Without a camundaBpmDataSource the engine runs on the application's datasource")
  public void withoutTheBeanTheApplicationsDataSourceIsUsed(
      final CapturedOutput output) {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:v1-ds-none;DB_CLOSE_DELAY=-1")
        .run(context -> {

          Assertions.assertSame(context.getBean(DataSource.class), engineDataSource(context));
          Assertions.assertSame(
              context.getBean(PlatformTransactionManager.class),
              engineConfiguration(context).getTransactionManager());
          Assertions.assertFalse(context.getBean(Camunda7EngineHolder.class).usesSeparateDataSource());
          Assertions.assertTrue(
              output
                  .getAllOfThisTest()
                  .contains("the engine runs on the application's default datasource with the application's "
                      + "transaction manager"),
              output::getAllOfThisTest);
          Assertions.assertFalse(output.getAllOfThisTest().contains(CHOSEN), output::getAllOfThisTest);

        });

  }

  @Test
  @DisplayName("The application's only datasource may be named camundaBpmDataSource, and the engine shares it")
  public void theOnlyDataSourceNamedCamundaBpmDataSourceIsShared(
      final CapturedOutput output) {

    final var only = h2("v1-ds-only");
    this.contextRunner
        .withBean("camundaBpmDataSource", DataSource.class, () -> only)
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .run(context -> {

          Assertions.assertSame(only, engineDataSource(context));
          // Spring Boot's transaction manager manages exactly this datasource. The engine
          // joins the application's transactions, as on any default datasource
          Assertions.assertSame(
              context.getBean(PlatformTransactionManager.class),
              engineConfiguration(context).getTransactionManager());
          Assertions.assertFalse(context.getBean(Camunda7EngineHolder.class).usesSeparateDataSource());
          Assertions.assertTrue(output.getAllOfThisTest().contains(CHOSEN), output::getAllOfThisTest);

        });

  }

  @Test
  @DisplayName("A camundaBpmDataSource next to the primary datasource gets the engine and a transaction manager of its own")
  public void aSecondDataSourceNamedCamundaBpmDataSourceIsTheEngines(
      final CapturedOutput output) {

    final var application = h2("v1-ds-primary-app");
    final var engine = h2("v1-ds-primary-engine");
    this.contextRunner
        .withBean("dataSource", DataSource.class, () -> application, definition -> definition.setPrimary(true))
        .withBean("camundaBpmDataSource", DataSource.class, () -> engine)
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            // the reserved value means the same as leaving the key out
            "vanillabp.adapters.c7.data-source-name=default")
        .run(context -> {

          Assertions.assertSame(engine, engineDataSource(context));
          final var transactionManager = engineConfiguration(context).getTransactionManager();
          Assertions.assertNotSame(context.getBean(PlatformTransactionManager.class), transactionManager);
          Assertions.assertSame(engine, ((DataSourceTransactionManager) transactionManager).getDataSource());
          // what the engine does follows the datasource it really runs on
          Assertions.assertTrue(context.getBean(Camunda7EngineHolder.class).usesSeparateDataSource());
          Assertions.assertTrue(
              output
                  .getAllOfThisTest()
                  .contains(CHOSEN
                      + " with a transaction manager of its own. It is not the application's default datasource. "
                      + "So the engine does not join the application's transactions"),
              output::getAllOfThisTest);

        });

  }

  @Test
  @DisplayName("Among several datasources without a primary one, camundaBpmDataSource is the choice")
  public void amongSeveralDataSourcesCamundaBpmDataSourceIsTheChoice() {

    final var engine = h2("v1-ds-several-engine");
    this.contextRunner
        .withBean("ordersDataSource", DataSource.class, () -> h2("v1-ds-several-orders"))
        .withBean("customersDataSource", DataSource.class, () -> h2("v1-ds-several-customers"))
        .withBean("camundaBpmDataSource", DataSource.class, () -> engine)
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .run(context -> {

          Assertions.assertSame(engine, engineDataSource(context));
          Assertions.assertTrue(context.getBean(Camunda7EngineHolder.class).usesSeparateDataSource());

        });

  }

  @Test
  @DisplayName("Among several datasources without camundaBpmDataSource, the start still ends and asks for a name")
  public void severalDataSourcesWithoutCamundaBpmDataSourceEndTheStart() {

    this.contextRunner
        .withBean("ordersDataSource", DataSource.class, () -> h2("v1-ds-none-orders"))
        .withBean("customersDataSource", DataSource.class, () -> h2("v1-ds-none-customers"))
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .run(context -> {

          final var message = rootCauseMessage(context.getStartupFailure());
          Assertions.assertTrue(message.contains("SEVERAL DataSource beans"), () -> message);
          Assertions.assertTrue(message.contains("vanillabp.adapters.c7.data-source-name"), () -> message);

        });

  }

  @Test
  @DisplayName("A camundaBpmTransactionManager is the engine's transaction manager, with or without camundaBpmDataSource")
  public void camundaBpmTransactionManagerIsTheEngines() {

    final var engine = h2("v1-tm-engine");
    final var engineTransactionManager = new DataSourceTransactionManager(engine);
    this.contextRunner
        .withBean("dataSource", DataSource.class, () -> h2("v1-tm-app"), definition -> definition.setPrimary(true))
        .withBean("camundaBpmDataSource", DataSource.class, () -> engine)
        .withBean(
            "transactionManager",
            PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(h2("v1-tm-app")),
            definition -> definition.setPrimary(true))
        .withBean("camundaBpmTransactionManager", PlatformTransactionManager.class, () -> engineTransactionManager)
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .run(context -> {

          Assertions.assertSame(engine, engineDataSource(context));
          Assertions.assertSame(engineTransactionManager, engineConfiguration(context).getTransactionManager());
          Assertions.assertTrue(context.getBean(Camunda7EngineHolder.class).usesSeparateDataSource());

        });

    // without camundaBpmDataSource the transaction manager is used on the application's
    // datasource, as in version 1
    final var application = h2("v1-tm-only-app");
    final var onlyTransactionManager = new DataSourceTransactionManager(application);
    this.contextRunner
        .withBean("dataSource", DataSource.class, () -> application)
        .withBean("camundaBpmTransactionManager", PlatformTransactionManager.class, () -> onlyTransactionManager)
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .run(context -> {

          Assertions.assertSame(application, engineDataSource(context));
          Assertions.assertSame(onlyTransactionManager, engineConfiguration(context).getTransactionManager());
          Assertions.assertFalse(context.getBean(Camunda7EngineHolder.class).usesSeparateDataSource());

        });

  }

  @Test
  @DisplayName("A datasource with the qualifier camundaBpmDataSource counts like one of that name")
  public void aQualifiedDataSourceCounts() {

    final var engine = h2("v1-ds-qualified-engine");
    this.contextRunner
        .withBean("dataSource", DataSource.class, () -> h2("v1-ds-qualified-app"), definition -> definition
            .setPrimary(true))
        .withBean("engineDatabase", DataSource.class, () -> engine, definition -> ((AbstractBeanDefinition) definition)
            .addQualifier(new AutowireCandidateQualifier(Qualifier.class, "camundaBpmDataSource")))
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .run(context -> Assertions.assertSame(engine, engineDataSource(context)));

  }

  @Test
  @DisplayName("A data-source-name wins over camundaBpmDataSource")
  public void aDataSourceNameWins() {

    final var named = h2("v1-ds-named");
    this.contextRunner
        .withBean("dataSource", DataSource.class, () -> h2("v1-ds-named-app"), definition -> definition
            .setPrimary(true))
        .withBean("camundaBpmDataSource", DataSource.class, () -> h2("v1-ds-named-v1"))
        .withBean("engineDataSource", DataSource.class, () -> named)
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "vanillabp.adapters.c7.data-source-name=engineDataSource")
        .run(context -> Assertions.assertSame(named, engineDataSource(context)));

  }

  private static String rootCauseMessage(
      final Throwable startupFailure) {

    Assertions.assertNotNull(startupFailure, "boot has to fail with a guiding message");
    var cause = startupFailure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
