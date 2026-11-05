package com.rightpath.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Read/write splitting against a MySQL primary and one read replica.
 *
 * <p>Off unless {@code app.datasource.replica.enabled=true}, in which case the application keeps the single
 * datasource Spring Boot builds from {@code spring.datasource.*}. Turning it on adds a second pool from
 * {@code app.datasource.replica.*} ({@code jdbc-url}, {@code username}, {@code password}, and optionally
 * {@code maximum-pool-size}) and routes
 * {@code @Transactional(readOnly = true)} work to it; see {@link ReplicaRoutingDataSource}.</p>
 *
 * <p>The connection is taken lazily so the read-only flag is known before the pool is chosen. Schema
 * updates at startup and ShedLock run outside a read-only transaction, so they stay on the primary.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.datasource.replica.enabled", havingValue = "true")
@EnableConfigurationProperties(DataSourceProperties.class)
public class ReadReplicaDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    public HikariDataSource replicaDataSource() {
        HikariDataSource replica = new HikariDataSource();
        replica.setReadOnly(true);
        replica.setPoolName("replica-pool");
        return replica;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("primaryDataSource") HikariDataSource primaryDataSource,
            @Qualifier("replicaDataSource") HikariDataSource replicaDataSource) {
        return new LazyConnectionDataSourceProxy(new ReplicaRoutingDataSource(primaryDataSource, replicaDataSource));
    }
}
