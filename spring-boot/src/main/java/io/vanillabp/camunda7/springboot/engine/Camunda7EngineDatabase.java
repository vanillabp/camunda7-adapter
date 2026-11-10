package io.vanillabp.camunda7.springboot.engine;

import java.util.Arrays;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import lombok.extern.slf4j.Slf4j;

/**
 * The database an engine runs on, where the adapter id names no datasource
 * (<code>data-source-name</code> unset or <code>default</code>). That is the application's
 * default datasource, or a bean <code>camundaBpmDataSource</code> where the application has
 * one, as in version 1. See {@link #choose}.
 *
 * @param dataSource The datasource the engine runs on
 * @param transactionManager The transaction manager the engine's commands run on
 * @param ownDataSource Whether the datasource is not the application's default datasource.
 *          Then the engine cannot join the caller's transaction, whatever the key says
 */
@Slf4j
public record Camunda7EngineDatabase(
                                     DataSource dataSource,
                                     PlatformTransactionManager transactionManager,
                                     boolean ownDataSource) {

  /**
   * The name version 1 looked for when it chose the engine's datasource. Camunda's Spring
   * Boot starter took a {@link DataSource} bean of this name or qualifier instead of the
   * application's default one.
   */
  public static final String VERSION_ONE_DATA_SOURCE = "camundaBpmDataSource";

  /**
   * The name version 1 looked for when it chose the engine's transaction manager, the same
   * way as {@link #VERSION_ONE_DATA_SOURCE}.
   */
  public static final String VERSION_ONE_TRANSACTION_MANAGER = "camundaBpmTransactionManager";

  /**
   * Chooses the database of an adapter id which names no datasource. A bean
   * <code>camundaBpmDataSource</code> wins over the application's default datasource, and
   * a bean <code>camundaBpmTransactionManager</code> over the application's transaction
   * manager. Version 1 chose the same way, so an application which gave its engine a
   * database of its own keeps it. Without this the engine would create empty tables in the
   * application's database, and nothing would report it.
   * <p>
   * Two things differ from version 1, both on purpose. With several datasources,
   * <code>camundaBpmDataSource</code> is the choice, and the start does not end. And a
   * <code>camundaBpmDataSource</code> which is not the application's default datasource,
   * without a <code>camundaBpmTransactionManager</code>, gets a transaction manager of the
   * adapter's own. Version 1 used the application's transaction manager there. That one
   * does not manage this datasource, so the statements of one engine command were not
   * committed together. What the engine does then follows the datasource it really gets:
   * it does not join the caller's transaction, and repeated deliveries are recognized.
   *
   * @param adapterId The adapter id
   * @param applicationContext Where the beans of version 1 are looked for
   * @param applicationDataSource The application's default datasource. It is only asked
   *          for where it is needed, and it ends the start with a guiding message where
   *          there is none or where there are several
   * @param applicationTransactionManager The application's transaction manager, asked for
   *          the same way
   * @return The datasource and transaction manager the engine runs on
   */
  public static Camunda7EngineDatabase choose(
      final String adapterId,
      final ApplicationContext applicationContext,
      final Supplier<DataSource> applicationDataSource,
      final Supplier<PlatformTransactionManager> applicationTransactionManager) {

    final var versionOneDataSource = qualifiedBean(applicationContext, DataSource.class, VERSION_ONE_DATA_SOURCE);
    final var versionOneTransactionManager = qualifiedBean(
        applicationContext, PlatformTransactionManager.class, VERSION_ONE_TRANSACTION_MANAGER);

    final DataSource dataSource;
    final boolean ownDataSource;
    if (versionOneDataSource == null) {
      refuseSeveralDataSources(adapterId, applicationContext);
      dataSource = applicationDataSource.get();
      ownDataSource = false;
    } else {
      dataSource = versionOneDataSource;
      // the application's default datasource is the one its own transactions run on. An
      // application whose only datasource is camundaBpmDataSource still shares it
      ownDataSource = applicationContext
          .getBeanProvider(DataSource.class)
          .getIfUnique() != versionOneDataSource;
    }

    final PlatformTransactionManager transactionManager;
    if (versionOneTransactionManager != null) {
      transactionManager = versionOneTransactionManager;
    } else if (ownDataSource) {
      transactionManager = new DataSourceTransactionManager(dataSource);
    } else {
      transactionManager = applicationTransactionManager.get();
    }

    log.info(
        "Camunda7[{}]: the engine runs on {} with {}. {}",
        adapterId,
        versionOneDataSource == null
            ? "the application's default datasource"
            : "the DataSource bean '%s', as in VanillaBP 1".formatted(VERSION_ONE_DATA_SOURCE),
        versionOneTransactionManager != null
            ? "the transaction manager bean '%s'".formatted(VERSION_ONE_TRANSACTION_MANAGER)
            : ownDataSource
                ? "a transaction manager of its own"
                : "the application's transaction manager",
        ownDataSource
            ? "It is not the application's default datasource. So the engine does not join the application's "
                + "transactions, and repeated deliveries are recognized."
            : "Engine and business data commit together.");

    return new Camunda7EngineDatabase(dataSource, transactionManager, ownDataSource);

  }

  /**
   * Ends the start where the application has several datasources and none of them is
   * <code>camundaBpmDataSource</code>. VanillaBP does not guess which one the engine runs
   * on. Not even a <code>&#64;Primary</code> bean decides it, because an embedded engine
   * writes its <code>ACT_*</code> tables into whatever database it gets. A datasource which
   * is no default candidate for injection does not count. Spring Boot keeps its own default
   * datasource for exactly that case, so such a bean is meant to be named.
   */
  private static void refuseSeveralDataSources(
      final String adapterId,
      final ApplicationContext applicationContext) {

    final var beanFactory = (ConfigurableListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
    final var candidates = Arrays
        .stream(beanFactory.getBeanNamesForType(DataSource.class, true, false))
        .filter(name -> isDefaultCandidate(beanFactory, name))
        .toList();
    if (candidates.size() < 2) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 7 adapter '%s' runs embedded and needs a database, but the application \
            provides SEVERAL DataSource beans: '%s'. Name the one this adapter id runs on:
              vanillabp.adapters.%s.data-source-name: <bean name>
            Use the reserved value 'default' for the application's default (primary) datasource. \
            (Two adapter ids may also share one datasource if each uses its own \
            'vanillabp.adapters.<id>.table-prefix' - Camunda does not create prefixed tables, so \
            those have to exist beforehand.)"""
            .formatted(adapterId, String.join("', '", candidates), adapterId));

  }

  private static boolean isDefaultCandidate(
      final ConfigurableListableBeanFactory beanFactory,
      final String name) {

    if (!beanFactory.containsBeanDefinition(name)) {
      // a singleton registered without a definition is injected by type like any other
      return true;
    }
    final var definition = beanFactory.getBeanDefinition(name);
    return definition
        .isAutowireCandidate() && (!(definition instanceof AbstractBeanDefinition abstractDefinition) || abstractDefinition
            .isDefaultCandidate());

  }

  /**
   * The bean of the given type which carries the qualifier, or has it as its name. This is
   * how Spring answered <code>@Qualifier</code> in Camunda's starter of version 1.
   *
   * @return The bean, or <code>null</code> where there is none
   */
  private static <S> S qualifiedBean(
      final ApplicationContext applicationContext,
      final Class<S> beanType,
      final String qualifier) {

    try {
      return BeanFactoryAnnotationUtils.qualifiedBeanOfType(applicationContext.getAutowireCapableBeanFactory(),
          beanType, qualifier);
    } catch (final NoUniqueBeanDefinitionException e) {
      // version 1 did not start with two such beans either. Choosing one of them here could
      // put the engine on the wrong database
      throw e;
    } catch (final NoSuchBeanDefinitionException e) {
      return null;
    }

  }

}
