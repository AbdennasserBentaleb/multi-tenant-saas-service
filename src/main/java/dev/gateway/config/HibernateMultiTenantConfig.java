package dev.gateway.config;

import dev.gateway.tenant.TenantContext;
import org.hibernate.HibernateException;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Enterprise-grade Hibernate 6 Multi-Tenancy Configuration.
 *
 * <p>
 * This replaces the flawed JDBC Proxy approach with native Hibernate connection
 * lifecycle hooks, entirely eliminating Reflection overhead and ensuring
 * mathematically sound connection pooling.
 *
 * <p>
 * If `RESET app.current_tenant` fails, the connection is aborted (evicted from
 * the pool) preventing catastrophic cross-tenant data bleed.
 */
@Configuration
public class HibernateMultiTenantConfig implements HibernatePropertiesCustomizer {

    private final MultiTenantConnectionProvider<String> multiTenantConnectionProvider;
    private final CurrentTenantIdentifierResolver<String> currentTenantIdentifierResolver;

    public HibernateMultiTenantConfig(
            MultiTenantConnectionProvider<String> multiTenantConnectionProvider,
            CurrentTenantIdentifierResolver<String> currentTenantIdentifierResolver) {
        this.multiTenantConnectionProvider = multiTenantConnectionProvider;
        this.currentTenantIdentifierResolver = currentTenantIdentifierResolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, multiTenantConnectionProvider);
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, currentTenantIdentifierResolver);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Component
    public static class RlsConnectionProvider implements MultiTenantConnectionProvider<String> {

        private static final Logger log = LoggerFactory.getLogger(RlsConnectionProvider.class);
        private final DataSource dataSource;

        public RlsConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getAnyConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void releaseAnyConnection(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        public Connection getConnection(String tenantIdentifier) throws SQLException {
            Connection connection = dataSource.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.current_tenant', '" + tenantIdentifier + "', false)");
                log.trace("Tenant [{}] bound to physical connection", tenantIdentifier);
            } catch (SQLException e) {
                connection.close();
                throw new HibernateException("Could not set tenant context on connection", e);
            }
            return connection;
        }

        @Override
        public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("RESET app.current_tenant");
                log.trace("Tenant [{}] released from physical connection", tenantIdentifier);
            } catch (SQLException e) {
                log.error("CRITICAL: Failed to reset tenant context. Aborting connection to prevent pool poisoning.", e);
                // Hard-evict the tainted connection from HikariCP
                try {
                    connection.abort(Runnable::run);
                } catch (Exception ex) {
                    // Ignored
                }
                throw new HibernateException("Could not reset tenant context. Connection aborted.", e);
            } finally {
                if (!connection.isClosed()) {
                    connection.close();
                }
            }
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }

        @Override
        public boolean isUnwrappableAs(Class<?> unwrapType) {
            return false;
        }

        @Override
        public <T> T unwrap(Class<T> unwrapType) {
            throw new UnsupportedOperationException("Unwrap not supported");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Component
    public static class ThreadLocalTenantResolver implements CurrentTenantIdentifierResolver<String> {

        private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000000";

        @Override
        public String resolveCurrentTenantIdentifier() {
            if (TenantContext.isBound()) {
                return TenantContext.getCurrentTenant().toString();
            }
            // Hibernate requires a non-null identifier even for schema generation or non-tenant requests.
            return DEFAULT_TENANT;
        }

        @Override
        public boolean validateExistingCurrentSessions() {
            return true;
        }
    }
}
