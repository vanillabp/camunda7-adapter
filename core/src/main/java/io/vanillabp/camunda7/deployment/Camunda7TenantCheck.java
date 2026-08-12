package io.vanillabp.camunda7.deployment;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.Tenant;

import lombok.extern.slf4j.Slf4j;

/**
 * What can be said about a Camunda 7 tenant before deploying into it.
 *
 * <h2>Camunda 7 needs no tenant to exist</h2>
 *
 * A tenant id is an ATTRIBUTE of a deployment and of the process definitions,
 * instances and tasks below it. The engine accepts any name, creates nothing and
 * deploys happily - so unlike Camunda 8 there is nothing that could reject the
 * deployment, and asking the engine "does this tenant exist" has no answer in the
 * engine's own terms.
 *
 * <h2>What the identity service knows</h2>
 *
 * Registered tenants ({@code ACT_ID_TENANT}, written by
 * {@link IdentityService#newTenant(String)}) exist for tenant MEMBERSHIPS and the
 * authorizations built on them. They are optional and most applications have none. An
 * application which does register them and forgets the one VanillaBP deploys into ends
 * up with a working deployment whose workflows nobody is authorized for, and that is
 * worth a warning. For everybody else this check stays silent.
 */
@Slf4j
public final class Camunda7TenantCheck {

  private Camunda7TenantCheck() {
  }

  /**
   * Warns if the given tenant is not registered in the identity service, but only if
   * the identity service knows tenants at all.
   * <p>
   * Every failure of the query is swallowed (a read-only identity provider such as LDAP
   * may not answer tenant queries): a diagnostic must never fail a deployment.
   *
   * @param adapterId The adapter ID
   * @param tenantId The tenant about to be deployed into
   * @param identityService The engine's identity service, or <code>null</code> to skip
   */
  public static void warnAboutUnregisteredTenant(
      final String adapterId,
      final String tenantId,
      final IdentityService identityService) {

    if ((identityService == null) || (tenantId == null)) {
      return;
    }
    try {
      final var registered = identityService
          .createTenantQuery()
          .list()
          .stream()
          .map(Tenant::getId)
          .toList();
      if (registered.isEmpty() || registered.contains(tenantId)) {
        return;
      }
      log.warn(
          """
              Camunda7[{}]: tenant '{}' is not registered in the engine's identity service, \
              although {} other tenant(s) are: {}. The deployment works anyway - Camunda 7 treats a \
              tenant id as an attribute of the deployment and creates nothing - but tenant \
              memberships and the authorizations built on them do not cover this tenant. Register it \
              (IdentityService#newTenant) or point the adapter at a registered one \
              ('vanillabp.adapters.{}.tenant-id'). Without that property the tenant is named after \
              the workflow module.""",
          adapterId,
          tenantId,
          registered.size(),
          registered,
          adapterId);
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda7[{}]: the identity service did not answer a tenant query, so tenant '{}' was not checked",
          adapterId,
          tenantId,
          e);
    }

  }

}
