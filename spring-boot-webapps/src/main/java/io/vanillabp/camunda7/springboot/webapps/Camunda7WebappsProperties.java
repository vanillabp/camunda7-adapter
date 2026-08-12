package io.vanillabp.camunda7.springboot.webapps;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * This module's OVERLAY of the shared <code>vanillabp.*</code> configuration tree: the
 * webapps are configured per adapter id, at
 * <code>vanillabp.adapters.&lt;id&gt;.webapps.*</code>, like every other setting of an
 * adapter instance. A second {@code @ConfigurationProperties} class over the same prefix
 * coexists with the platform's binding of the core model and with the adapter's engine
 * overlay; keys unknown to a view are ignored by the JavaBean binding.
 * <p>
 * The adapter-id set is NEVER derived from this map. It comes from the engines VanillaBP
 * actually built, and this overlay is a per-known-id lookup only.
 */
@ConfigurationProperties("vanillabp")
@Getter
@Setter
public class Camunda7WebappsProperties {

  /** The adapter sections of the shared tree, keyed by adapter id. */
  private Map<String, AdapterSection> adapters = Map.of();

  /**
   * The webapp settings of an adapter id, defaults if the section is absent.
   *
   * @param adapterId The adapter id
   * @return The settings (never <code>null</code>)
   */
  public Webapps webappsOf(
      final String adapterId) {

    final var section = adapters.get(adapterId);
    return (section == null) || (section.getWebapps() == null)
        ? new Webapps()
        : section.getWebapps();

  }

  /** One <code>vanillabp.adapters.&lt;id&gt;</code> section, webapp keys only. */
  @Getter
  @Setter
  public static class AdapterSection {

    private Webapps webapps;

  }

  /** The <code>webapps</code> section of an adapter id. */
  @Getter
  @Setter
  public static class Webapps {

    /**
     * Whether Cockpit, Tasklist and Admin serve this engine. On by default: an
     * application which added this module wants the webapps, and switching them off per
     * adapter id is the exception (e.g. an engine that only exists for a migration).
     */
    private boolean enabled = true;

    /**
     * The administrator to create on startup, if any. Without it this module creates no
     * user, and the webapps show their setup wizard - which is what an application
     * managing its own users wants.
     */
    private AdminUser adminUser;

  }

  /** The administrator created on startup. */
  @Getter
  @Setter
  public static class AdminUser {

    /** The user id used to log in. */
    private String id;

    /** The password. It is never logged, and never part of a message. */
    private String password;

    private String firstName;

    private String lastName;

    private String email;

  }

}
