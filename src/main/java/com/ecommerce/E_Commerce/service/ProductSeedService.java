package com.ecommerce.E_Commerce.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bulk test-data generator for the Phase-2 stress test / caching benchmark.
 *
 * <p>Uses a single PostgreSQL {@code generate_series} INSERT rather than
 * per-row JPA saves, so seeding thousands of products is one fast round trip.
 * {@code version} is set to 0 explicitly because raw SQL bypasses Hibernate's
 * {@code @Version} initialization.
 */
@Service
public class ProductSeedService {

    /** Safety ceiling so the endpoint can't be used to fill the disk. */
    public static final int MAX_SEED = 200_000;

    private final JdbcTemplate jdbc;

    public ProductSeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts {@code count} randomized products. Returns rows actually inserted. */
    public int seed(int count) {
        int n = Math.max(1, Math.min(count, MAX_SEED));
        return jdbc.update(
                "INSERT INTO products (name, price, stock_quantity, version) "
                        + "SELECT 'Product ' || g, "
                        + "round((random() * 100 + 1)::numeric, 2), "
                        + "(random() * 1000)::int + 1, "
                        + "0 "
                        + "FROM generate_series(1, ?) AS g",
                n);
    }

    /** Current number of products in the catalog. */
    public long count() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM products", Long.class);
        return total == null ? 0 : total;
    }
}
