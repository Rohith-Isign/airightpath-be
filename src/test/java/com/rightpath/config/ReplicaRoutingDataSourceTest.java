package com.rightpath.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rightpath.config.ReplicaRoutingDataSource.Route;

class ReplicaRoutingDataSourceTest {

    private final ReplicaRoutingDataSource routing =
            new ReplicaRoutingDataSource(mock(DataSource.class), mock(DataSource.class));

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void readOnlyTransactionsGoToTheReplica() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertEquals(Route.REPLICA, routing.determineCurrentLookupKey());
    }

    @Test
    void everythingElseGoesToThePrimary() {
        assertEquals(Route.PRIMARY, routing.determineCurrentLookupKey());
    }
}
