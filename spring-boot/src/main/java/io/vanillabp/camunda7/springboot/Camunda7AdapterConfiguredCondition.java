package io.vanillabp.camunda7.springboot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches if at least one adapter of type
 * {@value Camunda7AdapterConfiguration#ADAPTER_TYPE} is configured
 * (<code>vanillabp.adapters.&lt;id&gt;.type=camunda7</code>, or the adapter id itself
 * is <code>camunda7</code> without an explicit type). The embedded engine and all
 * Camunda 7 beans are gated by this condition: without it, ANY application having the
 * adapter jar plus a data source would get <code>ACT_*</code> tables created and a
 * running job executor in its business database.
 */
public class Camunda7AdapterConfiguredCondition implements Condition {

  /**
   * Minimal binding target for <code>vanillabp.adapters.&lt;id&gt;.*</code> - only
   * the <code>type</code> attribute is needed here.
   */
  public static class AdapterStub {

    private String type;

    public String getType() {
      return type;
    }

    public void setType(
        final String type) {
      this.type = type;
    }

  }

  @Override
  public boolean matches(
      final ConditionContext context,
      final AnnotatedTypeMetadata metadata) {

    return firstCamunda7AdapterId(context.getEnvironment()).isPresent();

  }

  /**
   * Resolves the first configured adapter id of type
   * {@value Camunda7AdapterConfiguration#ADAPTER_TYPE} (also used by the engine
   * configuration to read per-adapter properties like
   * <code>database-schema-update</code>).
   *
   * @param environment The environment to bind from
   * @return The first camunda7 adapter id or empty if none is configured
   */
  public static Optional<String> firstCamunda7AdapterId(
      final Environment environment) {

    final var adapters = Binder
        .get(environment)
        .bind("vanillabp.adapters", Bindable
            .mapOf(String.class, AdapterStub.class))
        .orElseGet(LinkedHashMap::new);
    return adapters
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7AdapterConfiguration.ADAPTER_TYPE.equals(
            adapter.getValue().getType() != null
                ? adapter.getValue().getType()
                : adapter.getKey()))
        .map(Map.Entry::getKey)
        .findFirst();

  }

}
