package com.ecommerce.E_Commerce.service;

import org.springframework.stereotype.Service;

/**
 * Pessimistic-locking counterpart to {@link LockedCheckoutService} — the
 * PESSIMISTIC_WRITE side of the Req 7 comparison.
 *
 * <p>No retry loop: the database lock serializes access, so a conflict can never
 * surface as an optimistic-lock failure. The caller simply blocks until it owns
 * the row, then proceeds.
 */
@Service
public class PessimisticCheckoutService {

    private final PessimisticCheckoutAttempt attempt;

    public PessimisticCheckoutService(PessimisticCheckoutAttempt attempt) {
        this.attempt = attempt;
    }

    public void purchase(Long productId, int quantity) {
        attempt.execute(productId, quantity);
    }
}
