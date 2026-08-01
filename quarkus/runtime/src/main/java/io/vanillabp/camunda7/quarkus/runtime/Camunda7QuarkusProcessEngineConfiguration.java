package io.vanillabp.camunda7.quarkus.runtime;

import org.camunda.bpm.engine.impl.cfg.JakartaTransactionProcessEngineConfiguration;

import jakarta.transaction.TransactionManager;

/**
 * Engine configuration for the plain Camunda 7 engine on Quarkus, per the recipe
 * proven by the plain-engine analysis probe: the engine-shipped
 * {@link JakartaTransactionProcessEngineConfiguration} (no
 * {@code camunda-engine-cdi-jakarta}, no Camunda Quarkus extension) with the CDI
 * {@link TransactionManager} (Narayana) - engine commands join the caller's JTA
 * transaction ({@code REQUIRED} semantics of the engine's
 * {@code JakartaTransactionInterceptor}), which is the embedded-engine phase-one
 * guarantee.
 * <p>
 * <b>Schema operations:</b> the engine's default runs schema commands OUTSIDE any
 * JTA transaction (a non-JTA command executor) - but Agroal has no deferred
 * enlistment, so connections outside a JTA transaction cannot be used and the
 * schema update fails. Overridden to run schema commands in their own JTA
 * transaction ({@code REQUIRES_NEW} executor) instead.
 */
public class Camunda7QuarkusProcessEngineConfiguration extends JakartaTransactionProcessEngineConfiguration {

  public Camunda7QuarkusProcessEngineConfiguration(
      final TransactionManager transactionManager) {

    setTransactionManager(transactionManager);
    // Narayana owns all transactions - the engine never begins/commits on its own
    setTransactionsExternallyManaged(true);

  }

  /**
   * Agroal has no deferred enlistment: run schema operations in their own JTA
   * transaction instead of the engine's non-JTA default (see class comment).
   */
  @Override
  protected void initCommandExecutorDbSchemaOperations() {

    if (commandExecutorSchemaOperations == null) {
      commandExecutorSchemaOperations = commandExecutorTxRequiresNew;
    }

  }

}
