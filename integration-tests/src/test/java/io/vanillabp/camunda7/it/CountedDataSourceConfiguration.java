package io.vanillabp.camunda7.it;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The application's datasource, with a count of every connection taken from it. A
 * connection is what anything in the application needs before it can say a word to the
 * database, so a count which does not move is a database nobody spoke to.
 * <p>
 * Declared as a bean of its own, which keeps Spring Boot's datasource auto-configuration
 * out of the way and leaves no pool running a keepalive of its own behind the measurement.
 * Used by {@link Camunda7SleepingEngineIT}.
 * <p>
 * A {@code @TestConfiguration} rather than a {@code @Configuration}, because the test
 * application scans this package: a plain configuration class here would hand its datasource
 * to every other integration test of the module as well.
 */
@TestConfiguration
public class CountedDataSourceConfiguration {

  /**
   * The count, static because the measuring test reads it while the application it belongs
   * to is running.
   */
  private static final AtomicInteger CONNECTIONS_TAKEN = new AtomicInteger();

  public static int connectionsTaken() {

    return CONNECTIONS_TAKEN.get();

  }

  @Bean
  public DataSource dataSource() {

    final var h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:c7-sleeping-engine-it;DB_CLOSE_DELAY=-1");
    return new CountedDataSource(h2);

  }

  private static final class CountedDataSource implements DataSource {

    private final JdbcDataSource delegate;

    CountedDataSource(
        final JdbcDataSource delegate) {

      this.delegate = delegate;

    }

    @Override
    public Connection getConnection() throws SQLException {

      CONNECTIONS_TAKEN.incrementAndGet();
      return delegate.getConnection();

    }

    @Override
    public Connection getConnection(
        final String username,
        final String password) throws SQLException {

      CONNECTIONS_TAKEN.incrementAndGet();
      return delegate.getConnection(username, password);

    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {

      return delegate.getLogWriter();

    }

    @Override
    public void setLogWriter(
        final PrintWriter out) throws SQLException {

      delegate.setLogWriter(out);

    }

    @Override
    public void setLoginTimeout(
        final int seconds) throws SQLException {

      delegate.setLoginTimeout(seconds);

    }

    @Override
    public int getLoginTimeout() throws SQLException {

      return delegate.getLoginTimeout();

    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {

      return delegate.getParentLogger();

    }

    @Override
    public <T> T unwrap(
        final Class<T> iface) throws SQLException {

      return delegate.unwrap(iface);

    }

    @Override
    public boolean isWrapperFor(
        final Class<?> iface) throws SQLException {

      return delegate.isWrapperFor(iface);

    }

  }

}
