package io.vanillabp.camunda7.springboot;

import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

/**
 * Makes a section written as <code>{}</code> keep the keys which other property sources
 * set below it.
 * <p>
 * <code>vanillabp.adapters.camunda7: {}</code> is the shortest way to configure an adapter
 * id whose type is its name. Spring Boot reads the empty map of a YAML file as a property
 * with an empty value, <code>vanillabp.adapters.camunda7=</code>. When Spring Boot binds an
 * object, it asks for a value at the object's own name first, in every property source,
 * and only then for the keys below it. So the empty value won, an empty section was bound,
 * and every key of that id from the command line, an environment variable,
 * <code>spring.application.json</code> or a second YAML document changed nothing. The
 * start did not say so.
 * <p>
 * A section of this adapter never has a value of its own. So each one is bound with
 * {@link Bindable.BindRestriction#NO_DIRECT_PROPERTY}, which tells Spring Boot to skip the
 * value at the section's name and read the keys below it. The rule is narrow on purpose:
 * it holds for the types of this adapter under <code>vanillabp</code> and leaves every
 * other binding of the application as it is.
 */
public final class Camunda7EmptySections {

  private Camunda7EmptySections() {

  }

  private static final String ROOT = "vanillabp";

  private static final String TYPES_OF_THIS_ADAPTER = "io.vanillabp.camunda7.";

  /**
   * The bindable of one section of this adapter, read from the keys below its name only.
   *
   * @param <T> The type of the section
   * @param sectionType The type of the section
   * @return The bindable to hand to a {@code Binder}
   */
  public static <T> Bindable<T> section(
      final Class<T> sectionType) {

    return Bindable
        .of(sectionType)
        .withBindRestrictions(Bindable.BindRestriction.NO_DIRECT_PROPERTY);

  }

  /**
   * The advisor which applies the same rule to {@link VanillaBpCamunda7Properties} and to
   * the overlay of the web applications module. Spring Boot binds both as
   * {@code @ConfigurationProperties}. There the sections are values of maps, so only a bind
   * handler reaches them.
   *
   * @return The advisor to register as a bean
   */
  public static ConfigurationPropertiesBindHandlerAdvisor advisor() {

    return SectionsKeepTheKeysBelowThem::new;

  }

  private static boolean isSectionOfThisAdapter(
      final ConfigurationPropertyName name,
      final Bindable<?> target) {

    if (name.isEmpty() || !ROOT.equals(name.getElement(0, ConfigurationPropertyName.Form.UNIFORM))) {
      return false;
    }
    final var type = target
        .getType()
        .resolve();
    return type != null && !type.isEnum() && type
        .getName()
        .startsWith(TYPES_OF_THIS_ADAPTER);

  }

  private static final class SectionsKeepTheKeysBelowThem extends AbstractBindHandler {

    private SectionsKeepTheKeysBelowThem(
        final BindHandler parent) {

      super(parent);

    }

    @Override
    public <T> Bindable<T> onStart(
        final ConfigurationPropertyName name,
        final Bindable<T> target,
        final BindContext context) {

      final var replacement = super.onStart(name, target, context);
      if (replacement == null || !isSectionOfThisAdapter(name, replacement)) {
        return replacement;
      }
      return replacement.withBindRestrictions(Bindable.BindRestriction.NO_DIRECT_PROPERTY);

    }

  }

}
