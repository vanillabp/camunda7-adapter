package io.vanillabp.camunda7.quarkus.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import io.agroal.api.AgroalDataSource;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;

/**
 * Produces the {@link Camunda7QuarkusEngineRegistry}: ONE embedded engine per
 * configured {@code camunda7} adapter id, built EAGERLY at startup (the
 * {@link Camunda7StartupObserver} forces this producer on {@code StartupEvent}) so
 * configuration defects surface at boot with guiding messages:
 * <ul>
 *   <li>each id's engine runs on the application's default Agroal datasource or -
 *       via <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code> - on a
 *       declared NAMED datasource (<code>quarkus.datasource.&lt;name&gt;.*</code>);
 *       an unknown name fails the boot listing the declared names;</li>
 *   <li>two embedded engines on one schema are the same engine state - more than
 *       one {@code camunda7} id resolving to the SAME datasource fails the boot
 *       naming the <code>data-source-name</code> remedy.</li>
 * </ul>
 * The adapter-id set comes from the platform's core properties (adapter ids of type
 * {@code camunda7}); the overlay map ({@link VanillaBpCamunda7Properties}) is a
 * per-known-id lookup only. The registry is closed on application shutdown via the
 * {@link #close(Camunda7QuarkusEngineRegistry) disposer}.
 */
@ApplicationScoped
public class Camunda7EngineProducer {

  @Produces
  @Singleton
  public Camunda7QuarkusEngineRegistry camunda7EngineRegistry(
      final MigrationAdapterProperties properties,
      final TransactionManager transactionManager,
      final WorkflowTaskRegistry workflowTaskRegistry,
      @Any final Instance<AgroalDataSource> dataSources) {

    final var overlay = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda7Properties.class);

    final var camunda7AdapterIds = properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7Adapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

    final var engines = new LinkedHashMap<String, Camunda7QuarkusEngineHolder>();
    camunda7AdapterIds
        .forEach(adapterId -> {
          final var keys = overlay.adapters().get(adapterId);
          final var dataSourceName = (keys == null)
              ? null
              : keys.dataSourceName().orElse(null);
          final var dataSource = resolveDataSource(dataSources, adapterId, dataSourceName);
          try {
            engines.put(adapterId, new Camunda7QuarkusEngineHolder(
                adapterId, toEngineProperties(
                    keys), dataSource, !io.vanillabp.camunda7.engine.Camunda7EngineProperties
                        .isDefaultDataSourceName(
                            dataSourceName), transactionManager, workflowTaskRegistry, workflowTaskRegistry, workflowTaskRegistry));
          } catch (final RuntimeException e) {
            throw new IllegalStateException(
                """
                    Camunda 7 adapter '%s': building the embedded engine on datasource '%s' failed! \
                    Check the datasource configuration ('quarkus.datasource.%s') and the engine keys \
                    'vanillabp.adapters.%s.*'."""
                    .formatted(
                        adapterId,
                        dataSourceName != null
                            ? dataSourceName
                            : "<default>",
                        dataSourceName != null
                            ? dataSourceName
                                + ".*"
                            : "*",
                        adapterId), e);
          }
        });

    return new Camunda7QuarkusEngineRegistry(engines);

  }

  public void close(
      @Disposes final Camunda7QuarkusEngineRegistry registry) {

    registry.close();

  }

  private static Camunda7EngineProperties toEngineProperties(
      final VanillaBpCamunda7Properties.Camunda7AdapterKeys keys) {

    final var properties = new Camunda7EngineProperties();
    if (keys == null) {
      return properties;
    }
    keys.databaseSchemaUpdate().ifPresent(properties::setDatabaseSchemaUpdate);
    keys.historyTimeToLive().ifPresent(properties::setHistoryTimeToLive);
    keys.tablePrefix().ifPresent(properties::setTablePrefix);
    keys.serializationFormat().ifPresent(properties::setSerializationFormat);
    // the named plugin sections travel onto the platform-neutral model, so the core builds
    // and configures them the same way on both platforms (story 66)
    properties
        .setEnginePlugins(
            keys
                .enginePlugins()
                .entrySet()
                .stream()
                .collect(
                    java.util.stream.Collectors
                        .toMap(
                            java.util.Map.Entry::getKey,
                            entry -> {
                              final var plugin = new io.vanillabp.camunda7.engine.Camunda7EnginePluginProperties();
                              plugin
                                  .setPluginClass(
                                      entry
                                          .getValue()
                                          .pluginClass()
                                          .orElse(null));
                              plugin
                                  .setProperties(
                                      entry
                                          .getValue()
                                          .properties());
                              return plugin;
                            })));
    return properties;

  }

  /**
   * Resolves the Agroal datasource the adapter id's engine runs on: the
   * application's default datasource, or - if <code>data-source-name</code> is
   * configured - the named datasource declared under
   * <code>quarkus.datasource.&lt;name&gt;.*</code>. Missing datasources fail with
   * a guiding message.
   */
  private static AgroalDataSource resolveDataSource(
      final Instance<AgroalDataSource> dataSources,
      final String adapterId,
      final String dataSourceName) {

    // the explicit @Default literal is required: with named datasources present, an
    // unqualified select() on the @Any instance would be ambiguous
    // several datasources declared: which one the engine runs on is not VanillaBP's
    // guess (story 34) - not even the default datasource decides it, because an
    // embedded engine writes its ACT_* tables into whatever database it gets. The
    // default datasource is named explicitly by the reserved value 'default'.
    if (dataSourceName == null) {
      final var declaredDataSources = declaredDataSourceNames(dataSources);
      if (declaredDataSources.size() > 1) {
        throw new IllegalStateException(
            """
                Camunda 7 adapter '%s' runs embedded and needs a database, but the application \
                declares SEVERAL datasources: %s. Name the one this adapter id runs on:
                  vanillabp.adapters.%s.data-source-name: <datasource name>
                Use the reserved value 'default' for the application's default datasource. \
                (Two adapter ids may also share one datasource if each uses its own \
                'vanillabp.adapters.<id>.table-prefix'.)"""
                .formatted(adapterId, declaredDataSources, adapterId));
      }
    }

    final Instance<AgroalDataSource> selected = io.vanillabp.camunda7.engine.Camunda7EngineProperties
        .isDefaultDataSourceName(dataSourceName)
            ? dataSources.select(jakarta.enterprise.inject.Default.Literal.INSTANCE)
            : dataSources.select(new io.quarkus.agroal.DataSource.DataSourceLiteral(dataSourceName));
    if (!selected.isResolvable()) {
      if (io.vanillabp.camunda7.engine.Camunda7EngineProperties.isDefaultDataSourceName(dataSourceName)) {
        throw new IllegalStateException(
            """
                Camunda 7 adapter '%s' is configured ('vanillabp.adapters.%s.type: camunda7') but no \
                default datasource is available. Camunda 7 runs embedded and always needs a database - \
                configure 'quarkus.datasource.*', reference a named datasource via \
                'vanillabp.adapters.%s.data-source-name' or remove the adapter configuration."""
                .formatted(adapterId, adapterId, adapterId));
      }
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' references the datasource '%s' \
              ('vanillabp.adapters.%s.data-source-name') but no such datasource is declared! Declare it \
              via 'quarkus.datasource.%s.*' (named Quarkus datasources are build-time-declared). \
              Declared datasources: %s."""
              .formatted(adapterId, dataSourceName, adapterId, dataSourceName, declaredDataSourceNames(dataSources)));
    }
    return selected.get();

  }

  /**
   * The names of all declared Agroal datasources (for guiding messages) -
   * <code>&lt;default&gt;</code> for the unnamed application datasource.
   */
  private static List<String> declaredDataSourceNames(
      final Instance<AgroalDataSource> dataSources) {

    final var names = new LinkedList<String>();
    dataSources
        .handles()
        .forEach(handle -> handle
            .getBean()
            .getQualifiers()
            .forEach(qualifier -> {
              if (qualifier instanceof io.quarkus.agroal.DataSource dataSourceQualifier) {
                names.add(dataSourceQualifier.value());
              } else if (qualifier instanceof jakarta.enterprise.inject.Default) {
                names.add("<default>");
              }
            }));
    return names;

  }

}
