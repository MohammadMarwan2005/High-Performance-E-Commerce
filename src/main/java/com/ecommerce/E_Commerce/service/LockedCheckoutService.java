package com.ecommerce.E_Commerce.service;

import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Correct stock decrement under contention — the WITH-lock side of the
 * Req 1 proof. Wraps {@link LockedCheckoutAttempt} with a bounded retry
 * loop. The transaction lives in the attempt bean so the retry is OUTSIDE
 * the transaction (a rolled-back tx can't be reused).
 */
@Service
public class LockedCheckoutService {

    private static final int MAX_ATTEMPTS = 3;

    private final LockedCheckoutAttempt attempt;

    public LockedCheckoutService(LockedCheckoutAttempt attempt) {
        this.attempt = attempt;
    }

    public void purchase(Long productId, int quantity) {
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            try {
                attempt.execute(productId, quantity);
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                if (i == MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "conflict after " + MAX_ATTEMPTS + " retries", e);
                }
            }
        }
    }
}
