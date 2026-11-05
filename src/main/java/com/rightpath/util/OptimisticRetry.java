package com.rightpath.util;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Re-runs an operation that lost an optimistic-lock race.
 *
 * <p>Must wrap the call to a transactional service from outside it: the failed
 * transaction has been rolled back, so each attempt re-reads the row and sees
 * the other pod's change. Only lock conflicts are retried; anything else
 * propagates on the first throw.</p>
 */
public final class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);

    private static final int ATTEMPTS = 3;

    private OptimisticRetry() {
    }

    public static <T> T run(Supplier<T> operation) {
        for (int attempt = 1;; attempt++) {
            try {
                return operation.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                log.info("Optimistic lock conflict; retrying (attempt {} of {})", attempt + 1, ATTEMPTS);
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
