package com.rightpath.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * With splitting on, a read-only transaction opens its connection on the replica pool and a writing
 * one never touches it. The replica here is the same database, which is enough to see the routing.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "aws.s3.access-key=test-access-key",
        "aws.s3.secret-key=test-secret-key",
        "app.datasource.replica.enabled=true",
        "app.datasource.replica.jdbc-url=${spring.datasource.url}",
        "app.datasource.replica.username=${spring.datasource.username}",
        "app.datasource.replica.password=${spring.datasource.password}",
})
class ReadReplicaRoutingIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    @Qualifier("replicaDataSource")
    private HikariDataSource replica;

    @Test
    void onlyReadOnlyTransactionsReachTheReplica() {
        TransactionTemplate write = new TransactionTemplate(transactions);
        write.execute(status -> jdbc.queryForObject("select 1", Integer.class));
        assertNull(replica.getHikariPoolMXBean(), "a writing transaction must not open the replica pool");

        TransactionTemplate read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.execute(status -> jdbc.queryForObject("select 1", Integer.class));
        assertNotNull(replica.getHikariPoolMXBean(), "a read-only transaction must use the replica pool");
    }
}
