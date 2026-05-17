package com.ecommerce.E_Commerce.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEMO ONLY — race-prone stock decrement.
 *
 * <p>This bean exists to prove the lost-update race that Requirement 1 is
 * about. It bypasses JPA's {@code @Version} optimistic lock by going through
 * raw {@link JdbcTemplate}, so concurrent callers can both observe
 * {@code stock_quantity = 1}, both pass the check, and both write the
 * decremented value — overselling the product.
 *
 * <p>Do <strong>not</strong> use this from production code paths. The real
 * checkout goes through
 * {@link OrderCheckoutTransaction#execute} which has the version-based
 * synchronization point.
 */
@Service
public class NaiveCheckoutService {

    private final JdbcTemplate jdbc;

    public NaiveCheckoutService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void purchase(Long productId, int quantity) {
        Integer stock = jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class, productId);

        if (stock == null || stock < quantity) {
            throw new IllegalStateException("insufficient stock");
        }

        // Widen the read→write window so the race manifests on every run.
        // The bug exists without this sleep too; the sleep just removes the
        // flakiness so the demo is reproducible.
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        // NO version check — this UPDATE always wins, even if the row was
        // changed between the SELECT above and here. That is the bug.
        jdbc.update(
                "UPDATE products SET stock_quantity = ? WHERE id = ?",
                stock - quantity, productId);
    }
}
