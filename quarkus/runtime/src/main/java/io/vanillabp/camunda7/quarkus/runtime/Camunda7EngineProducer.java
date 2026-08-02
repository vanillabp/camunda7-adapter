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

    validateDistinctDataSources(camunda7AdapterIds, overlay);

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
                    keys), dataSource, dataSourceName != null, transactionManager, workflowTaskRegistry));
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
    final Instance<AgroalDataSource> selected = dataSourceName == null
        ? dataSources.select(jakarta.enterprise.inject.Default.Literal.INSTANCE)
        : dataSources.select(new io.quarkus.agroal.DataSource.DataSourceLiteral(dataSourceName));
    if (!selected.isResolvable()) {
      if (dataSourceName == null) {
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

  /**
   * Fails the boot if more than one {@code camunda7} adapter id resolves to the
   * same datasource: two embedded engines on one schema are the same engine state -
   * configuring them as two adapters is an error (validated at startup, 26c style).
   */
  private static void validateDistinctDataSources(
      final List<String> camunda7AdapterIds,
      final VanillaBpCamunda7Properties overlay) {

    if (camunda7AdapterIds.size() < 2) {
      return;
    }

    final var idsByDataSource = new LinkedHashMap<String, List<String>>();
    camunda7AdapterIds
        .forEach(adapterId -> {
          final var keys = overlay.adapters().get(adapterId);
          final var effectiveDataSource = (keys == null)
              ? "<the application's default datasource>"
              : keys
                  .dataSourceName()
                  .orElse("<the application's default datasource>");
          idsByDataSource
              .computeIfAbsent(effectiveDataSource, key -> new LinkedList<>())
              .add(adapterId);
        });

    idsByDataSource
        .forEach((
            dataSource,
            adapterIds) -> {
          if (adapterIds.size() < 2) {
            return;
          }
          throw new IllegalStateException(
              """
                  The Camunda 7 adapters '%s' would share the same datasource (%s)! Two embedded \
                  engines on one schema are the same engine state - configuring them as separate \
                  adapters is an error. Give each additional adapter its own datasource: declare it \
                  via 'quarkus.datasource.<name>.*' and reference it via \
                  'vanillabp.adapters.<id>.data-source-name', or remove all but one of these adapters."""
                  .formatted(String.join("', '", adapterIds), dataSource));
        });

  }

}
