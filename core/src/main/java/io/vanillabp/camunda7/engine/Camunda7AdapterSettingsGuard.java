package io.vanillabp.camunda7.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the engine plugins of the application while the engine is built. A plugin runs
 * after the adapter set up the engine configuration. So it could change a setting which the
 * adapter sets itself without anybody noticing, for example switch off
 * {@code jobExecutorDeploymentAware} or replace the id generator. This class notices it.
 * <p>
 * It puts a small plugin of its own before, between and after the plugins of the
 * application. Each of them compares the settings with the ones the one before it saw. So a
 * change is always reported with the plugin which made it. This happens in each of the three
 * steps of a plugin: {@code preInit}, {@code postInit} and {@code postProcessEngineBuild}.
 * The engine fills some settings itself between two steps. That is why each step starts from
 * what the engine holds when the step begins.
 * <p>
 * A changed setting ends the start, unless
 * <code>vanillabp.adapters.&lt;id&gt;.allow-plugins-to-change-adapter-settings</code> is
 * <code>true</code>. Then each changed setting gets a WARN and the start goes on. Any setting
 * which the adapter does not set itself stays free for a plugin. The list of settings is the
 * one which <code>client-configuration-inventory.yml</code> marks as {@code set-by-adapter}
 * on the engine surface. See {@code DECISIONS.pending/2119.md} in the repository.
 */
public final class Camunda7AdapterSettingsGuard {

  private static final Logger log = LoggerFactory.getLogger(Camunda7AdapterSettingsGuard.class);

  /**
   * The key below <code>vanillabp.adapters.&lt;id&gt;</code> which lets a plugin change a
   * setting of the adapter with a warning instead of ending the start.
   */
  public static final String ALLOW_PLUGINS_TO_CHANGE_ADAPTER_SETTINGS_KEY = "allow-plugins-to-change-adapter-settings";

  /**
   * One setting the adapter sets itself, and how to read it from the configuration.
   *
   * @param name The name of the setting, as the inventory lists it
   * @param read Reads the current value
   * @param list Whether the value is a list the adapter puts entries into. A plugin may add
   *          entries to such a list, but it may not remove the adapter's
   */
  public record Setting(
                        String name,
                        Function<ProcessEngineConfigurationImpl, Object> read,
                        boolean list) {

    /**
     * A setting with a single value.
     *
     * @param name The name of the setting
     * @param read Reads the current value
     * @return The setting
     */
    public static Setting value(
        final String name,
        final Function<ProcessEngineConfigurationImpl, Object> read) {

      return new Setting(name, read, false);

    }

    /**
     * A setting which is a list of entries.
     *
     * @param name The name of the setting
     * @param read Reads the current list
     * @return The setting
     */
    public static Setting list(
        final String name,
        final Function<ProcessEngineConfigurationImpl, Object> read) {

      return new Setting(name, read, true);

    }

  }

  /**
   * The settings both platforms set. A platform adds the ones only it has, like the
   * transaction manager, whose type differs per platform.
   */
  private static final List<Setting> SETTINGS_OF_BOTH_PLATFORMS = List
      .of(
          Setting.value("classLoader", ProcessEngineConfigurationImpl::getClassLoader),
          Setting.value("processEngineName", ProcessEngineConfigurationImpl::getProcessEngineName),
          Setting.value("transactionsExternallyManaged",
              ProcessEngineConfigurationImpl::isTransactionsExternallyManaged),
          Setting.list("customPreBPMNParseListeners", ProcessEngineConfigurationImpl::getCustomPreBPMNParseListeners),
          Setting.list("customPostBPMNParseListeners", ProcessEngineConfigurationImpl::getCustomPostBPMNParseListeners),
          Setting.value("expressionManager", ProcessEngineConfigurationImpl::getExpressionManager),
          Setting.value("historyEventHandler", ProcessEngineConfigurationImpl::getHistoryEventHandler),
          Setting
              .list(
                  "customPostCommandInterceptorsTxRequired",
                  ProcessEngineConfigurationImpl::getCustomPostCommandInterceptorsTxRequired),
          Setting
              .list(
                  "customPostCommandInterceptorsTxRequiresNew",
                  ProcessEngineConfigurationImpl::getCustomPostCommandInterceptorsTxRequiresNew),
          Setting.value("jobExecutor", ProcessEngineConfigurationImpl::getJobExecutor),
          Setting.value("jobExecutorActivate", ProcessEngineConfigurationImpl::isJobExecutorActivate),
          Setting.value("idGenerator", ProcessEngineConfigurationImpl::getIdGenerator),
          Setting.value("jobExecutorDeploymentAware", ProcessEngineConfigurationImpl::isJobExecutorDeploymentAware));

  private Camunda7AdapterSettingsGuard() {
  }

  /**
   * The key spelled out for one adapter id, for a message.
   *
   * @param adapterId The adapter id
   * @return {@code vanillabp.adapters.<id>.allow-plugins-to-change-adapter-settings}
   */
  public static String propertyKeyOf(
      final String adapterId) {

    return "vanillabp.adapters.%s.%s".formatted(adapterId, ALLOW_PLUGINS_TO_CHANGE_ADAPTER_SETTINGS_KEY);

  }

  /**
   * The names of every setting watched on a platform, for tests and documentation.
   *
   * @param platformSettings The settings only this platform has
   * @return The names, the ones of both platforms first
   */
  public static List<String> namesOf(
      final Collection<Setting> platformSettings) {

    final var names = new ArrayList<String>();
    SETTINGS_OF_BOTH_PLATFORMS.forEach(setting -> names.add(setting.name()));
    platformSettings.forEach(setting -> names.add(setting.name()));
    return names;

  }

  /**
   * Puts the watching plugins around the plugins the configuration holds. Call it right
   * before the engine is built, after the adapter and every engine customizer set what they
   * set. Without a plugin of the application nothing can change a setting later, and the
   * configuration stays as it is.
   *
   * @param adapterId The adapter id whose engine is built
   * @param configuration The configuration, with every plugin of the application in it
   * @param allowed Whether a changed setting only gets a WARN
   * @param platformSettings The settings only this platform has
   */
  public static void watch(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration,
      final boolean allowed,
      final Collection<Setting> platformSettings) {

    final var plugins = new ArrayList<>(configuration.getProcessEnginePlugins());
    if (plugins.isEmpty()) {
      return;
    }
    final var settings = new ArrayList<>(SETTINGS_OF_BOTH_PLATFORMS);
    settings.addAll(platformSettings);
    final var watch = new Watch(adapterId, allowed, settings, plugins);

    final var watched = new ArrayList<ProcessEnginePlugin>();
    watched.add(new Probe(watch, 0));
    for (var index = 0; index < plugins.size(); ++index) {
      watched.add(plugins.get(index));
      watched.add(new Probe(watch, index + 1));
    }
    configuration.setProcessEnginePlugins(watched);

  }

  /**
   * Reads a field which has no getter. The Spring configuration of the engine keeps its
   * application context like this.
   *
   * @param declaringClass The class which declares the field
   * @param fieldName The name of the field
   * @return Reads the field of a configuration
   * @throws IllegalStateException If the field is not there. Then a new Camunda version
   *           renamed it, and a test of this adapter fails
   */
  public static Function<ProcessEngineConfigurationImpl, Object> fieldOf(
      final Class<? extends ProcessEngineConfigurationImpl> declaringClass,
      final String fieldName) {

    final java.lang.reflect.Field field;
    try {
      field = declaringClass.getDeclaredField(fieldName);
      field.setAccessible(true);
    } catch (final NoSuchFieldException | RuntimeException notThere) {
      throw new IllegalStateException(
          "%s has no field '%s' any more. Read the setting some other way."
              .formatted(declaringClass.getName(), fieldName), notThere);
    }
    return configuration -> {
      try {
        return field.get(configuration);
      } catch (final IllegalAccessException cannotRead) {
        throw new IllegalStateException(cannotRead);
      }
    };

  }

  /**
   * The name of a plugin's class, without the class a container generated around it.
   */
  static String nameOf(
      final ProcessEnginePlugin plugin) {

    var name = plugin
        .getClass()
        .getName();
    // a CDI bean of Quarkus is reached through a client proxy, and a Spring bean may be a
    // CGLIB subclass. The developer wrote the class without the suffix
    final var proxy = name.indexOf("_ClientProxy");
    if (proxy > 0) {
      name = name.substring(0, proxy);
    }
    final var cglib = name.indexOf("$$");
    if (cglib > 0) {
      name = name.substring(0, cglib);
    }
    return name;

  }

  /**
   * A value as a message shows it: simple values as they are, anything else by its class.
   */
  static String describe(
      final Object value) {

    if (value == null) {
      return "nothing";
    }
    if ((value instanceof Boolean) || (value instanceof Number) || (value instanceof String) || (value instanceof Enum<?>)) {
      return "'%s'".formatted(value);
    }
    return value
        .getClass()
        .getName();

  }

  /**
   * One setting a plugin changed, as one line of a message.
   *
   * @param setting The name of the setting
   * @param plugin The class of the plugin which changed it
   * @param step The step of the plugin which changed it
   * @param text What changed
   */
  record Change(
                String setting,
                String plugin,
                String step,
                String text) {
  }

  /**
   * What the probes of one engine share: the plugins, the values each step started with,
   * the values the probe before saw, and the changes found in the current step.
   */
  static final class Watch {

    private final String adapterId;

    private final boolean allowed;

    private final List<Setting> settings;

    private final List<ProcessEnginePlugin> plugins;

    private Map<String, Object> atTheStart;

    private Map<String, Object> seenBefore;

    private final List<Change> changes = new ArrayList<>();

    Watch(
        final String adapterId,
        final boolean allowed,
        final List<Setting> settings,
        final List<ProcessEnginePlugin> plugins) {

      this.adapterId = adapterId;
      this.allowed = allowed;
      this.settings = settings;
      this.plugins = plugins;

    }

    void begin(
        final ProcessEngineConfigurationImpl configuration) {

      atTheStart = valuesOf(configuration);
      seenBefore = atTheStart;
      changes.clear();

    }

    void compare(
        final ProcessEngineConfigurationImpl configuration,
        final int pluginIndex,
        final String step) {

      final var now = valuesOf(configuration);
      final var plugin = nameOf(plugins.get(pluginIndex));
      for (final var setting : settings) {
        final var name = setting.name();
        final var start = atTheStart.get(name);
        final var before = seenBefore.get(name);
        final var current = now.get(name);
        if (setting.list()) {
          final var removed = missing(start, current);
          removed.removeAll(missing(start, before));
          if (!removed.isEmpty()) {
            changes
                .add(new Change(name, plugin, step, "the plugin %s removed what the adapter put there (%s) in %s"
                    .formatted(
                        plugin,
                        removed
                            .stream()
                            .map(Camunda7AdapterSettingsGuard::describe)
                            .collect(Collectors.joining(", ")),
                        step)));
          }
        } else if (!Objects.equals(current, before) && !Objects.equals(current, start)) {
          final var startText = describe(start);
          final var currentText = describe(current);
          changes
              .add(new Change(name, plugin, step, "the adapter set %s, and the plugin %s changed it to %s in %s"
                  .formatted(
                      startText,
                      plugin,
                      startText.equals(currentText)
                          ? "another "
                              + currentText
                          : currentText,
                      step)));
        }
      }
      seenBefore = now;

    }

    void finish(
        final String step,
        final ProcessEngine builtEngine) {

      if (changes.isEmpty()) {
        return;
      }
      final var key = propertyKeyOf(adapterId);
      if (allowed) {
        changes
            .forEach(change -> log
                .warn(
                    """
                        Camunda7[{}]: the engine plugin {} changed the setting '{}', which the adapter \
                        sets itself: {}. '{}' allows this. A feature of VanillaBP which needs the \
                        adapter's value may not work any more. The file client-configuration-inventory.yml \
                        of the adapter says why the adapter sets it.""",
                    adapterId,
                    change.plugin(),
                    change.setting(),
                    change.text(),
                    key));
        changes.clear();
        return;
      }
      final var lines = changes
          .stream()
          .map(change -> "  - '%s': %s".formatted(change.setting(), change.text()))
          .collect(Collectors.joining("\n"));
      changes.clear();
      if (builtEngine != null) {
        // the engine exists already in the last step. It must not keep running
        builtEngine.close();
      }
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s': an engine plugin of your application changed settings which \
              the adapter sets itself!
              %s
              VanillaBP needs these values. The file client-configuration-inventory.yml of the \
              adapter says why for each of them. Remove the change from the plugin. If your \
              application really needs it, set '%s: true'. Then the start goes on, with a warning \
              for each setting a plugin changed."""
              .formatted(adapterId, lines, key));

    }

    private Map<String, Object> valuesOf(
        final ProcessEngineConfigurationImpl configuration) {

      final var values = new HashMap<String, Object>();
      for (final var setting : settings) {
        final var value = setting
            .read()
            .apply(configuration);
        if (setting.list()) {
          // a copy, because the plugin changes the list the configuration holds
          values
              .put(setting.name(), value == null
                  ? new ArrayList<>()
                  : new ArrayList<>((Collection<?>) value));
        } else {
          values.put(setting.name(), value);
        }
      }
      return values;

    }

    /**
     * The entries of the first list which are not in the second one. The same object has to
     * be there, an equal one is not enough.
     */
    private static List<Object> missing(
        final Object expected,
        final Object actual) {

      final var result = new ArrayList<>();
      for (final var entry : (List<?>) expected) {
        if (((List<?>) actual)
            .stream()
            .noneMatch(candidate -> candidate == entry)) {
          result.add(entry);
        }
      }
      return result;

    }

  }

  /**
   * One of the plugins around the plugins of the application. The first one of a step
   * remembers the settings. Every other one compares them, and the last one ends the start
   * or warns.
   */
  static final class Probe implements ProcessEnginePlugin {

    private final Watch watch;

    /** 0 for the probe before the first plugin, n for the one after the n-th plugin. */
    private final int position;

    Probe(
        final Watch watch,
        final int position) {

      this.watch = watch;
      this.position = position;

    }

    private void look(
        final ProcessEngineConfigurationImpl configuration,
        final String step,
        final ProcessEngine builtEngine) {

      if (position == 0) {
        watch.begin(configuration);
        return;
      }
      watch.compare(configuration, position - 1, step);
      if (position == watch.plugins.size()) {
        watch.finish(step, builtEngine);
      }

    }

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl configuration) {

      look(configuration, "preInit", null);

    }

    @Override
    public void postInit(
        final ProcessEngineConfigurationImpl configuration) {

      look(configuration, "postInit", null);

    }

    @Override
    public void postProcessEngineBuild(
        final ProcessEngine processEngine) {

      look(
          (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration(),
          "postProcessEngineBuild",
          processEngine);

    }

    @Override
    public String toString() {

      return "%s(%d)".formatted(Camunda7AdapterSettingsGuard.class.getSimpleName(), position);

    }

  }

}
