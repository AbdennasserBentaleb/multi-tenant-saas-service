package dev.gateway.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Configures the primary {@link HikariDataSource}.
 *
 * <p>
 * Connection pool safety and PostgreSQL Row-Level Security (RLS) injection
 * are handled gracefully by Hibernate's native MultiTenantConnectionProvider
 * rather than high-overhead JDBC Proxies.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
