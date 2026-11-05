package com.rightpath.config;

import java.util.Map;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/**
 * Sends read-only transactions to the replica and everything else to the primary.
 *
 * <p>Only a transaction that is declared {@code @Transactional(readOnly = true)} can reach the replica.
 * Anything outside a transaction, and every writing transaction, uses the primary, so a read that
 * follows a write in the same flow never sees replica lag.</p>
 */
public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

    public enum Route { PRIMARY, REPLICA }

    public ReplicaRoutingDataSource(DataSource primary, DataSource replica) {
        setTargetDataSources(Map.of(Route.PRIMARY, primary, Route.REPLICA, replica));
        setDefaultTargetDataSource(primary);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? Route.REPLICA : Route.PRIMARY;
    }
}
