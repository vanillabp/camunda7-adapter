package io.vanillabp.camunda7.quarkus.test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Counts every connection handed out by the application's pool. A connection is what
 * anything in the application needs before it can say a word to the database, so a count
 * which does not move is a database nobody spoke to, which is what the Camunda 7 engine's
 * due-date sleep is worth measuring.
 * <p>
 * An Agroal pool interceptor rather than the pool's metrics: those need a metrics extension
 * on the classpath, and a counter is the whole question here.
 */
@ApplicationScoped
public class C7ConnectionsTaken implements AgroalPoolInterceptor {

  private static final AtomicLong TAKEN = new AtomicLong();

  @Override
  public void onConnectionAcquire(
      final Connection connection) {

    TAKEN.incrementAndGet();

  }

  /**
   * @return How many connections were taken since the application started
   */
  public long count() {

    return TAKEN.get();

  }

}
