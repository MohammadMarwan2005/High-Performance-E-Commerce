package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs the Requirement-1 concurrency proof on demand and returns the same
 * report text the JUnit harness writes to docs/req1-proof/*.txt — but live,
 * against the running server, with caller-supplied parameters.
 *
 * <p>This is the production-callable twin of {@code ConcurrencyProofIT}. The
 * heavy lifting (the actual race) is identical; only the trigger differs (an
 * HTTP request instead of a test method).
 */
@Service
public class ConcurrencyProofService {

    private static final String DEMO_PRODUCT = "RACE_DEMO";

    private final NaiveCheckoutService naive;
    private final LockedCheckoutService locked;
    private final ProductRepository productRepository;
    private final JdbcTemplate jdbc;

    public ConcurrencyProofService(NaiveCheckoutService naive,
                                   LockedCheckoutService locked,
                                   ProductRepository productRepository,
                                   JdbcTemplate jdbc) {
        this.naive = naive;
        this.locked = locked;
        this.productRepository = productRepository;
        this.jdbc = jdbc;
    }

    /** Run the requested mode(s) and return the concatenated report text. */
    public String run(int threads, int initialStock, int quantityEach, String mode) {
        StringBuilder out = new StringBuilder();

        if (mode.equals("naive") || mode.equals("both")) {
            Long productId = resetDemoProduct(initialStock);
            Stats stats = race(productId, threads, quantityEach, naive::purchase);
            out.append(report(
                    "WITHOUT optimistic locking",
                    "NaiveCheckoutService (raw SQL, no @Version)",
                    threads, initialStock, quantityEach, stats));
        }

        if (mode.equals("locked") || mode.equals("both")) {
            if (out.length() > 0) out.append("\n");
            Long productId = resetDemoProduct(initialStock);
            Stats stats = race(productId, threads, quantityEach, locked::purchase);
            out.append(report(
                    "WITH optimistic locking + retry",
                    "LockedCheckoutService (@Version on Product, 3 retries)",
                    threads, initialStock, quantityEach, stats));
        }

        return out.toString();
    }

    /** Create or reset the shared demo product to the requested stock. */
    private Long resetDemoProduct(int initialStock) {
        Optional<Product> existing = productRepository.findAll().stream()
                .filter(p -> DEMO_PRODUCT.equals(p.getName()))
                .findFirst();
        Product product = existing.orElseGet(() -> Product.builder()
                .name(DEMO_PRODUCT)
                .price(new BigDecimal("9.99"))
                .stockQuantity(initialStock)
                .build());
        product.setStockQuantity(initialStock);
        return productRepository.save(product).getId();
    }

    private Stats race(Long productId, int threads, int quantityEach, Purchase purchase) {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficientStock = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    purchase.run(productId, quantityEach);
                    successes.incrementAndGet();
                } catch (IllegalStateException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("insufficient stock")) {
                        insufficientStock.incrementAndGet();
                    } else if (msg.contains("conflict")) {
                        conflicts.incrementAndGet();
                    } else {
                        otherFailures.incrementAndGet();
                    }
                } catch (Throwable t) {
                    otherFailures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        long t0 = System.nanoTime();
        start.countDown();
        try {
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();

        Integer finalStock = jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class, productId);

        return new Stats(
                successes.get(),
                insufficientStock.get(),
                conflicts.get(),
                otherFailures.get(),
                finalStock == null ? -9999 : finalStock,
                elapsedMs);
    }

    private String report(String title, String mode,
                          int threads, int initialStock, int quantityEach,
                          Stats s) {
        int totalDemand = threads * quantityEach;
        boolean oversold = s.successes > (initialStock / Math.max(1, quantityEach));
        return """
                === Concurrency Proof — %s ===
                Generated: %s
                Mode:      %s

                Setup:
                  Threads:        %d (concurrent)
                  Initial stock:  %d
                  Quantity each:  %d
                  Total demand:   %d
                  Available:      %d

                Result:
                  Successful orders:   %d
                  Failed (no stock):   %d
                  Failed (conflict):   %d
                  Failed (other):      %d
                  Final stock:         %d
                  Elapsed:             %d ms

                Oversell:  %s
                """.formatted(
                title,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                mode,
                threads, initialStock, quantityEach, totalDemand, initialStock,
                s.successes, s.insufficientStock, s.conflicts, s.otherFailures,
                s.finalStock, s.elapsedMs,
                oversold ? "YES — bug demonstrated" : "NO — invariant held");
    }

    @FunctionalInterface
    interface Purchase {
        void run(Long productId, int qty);
    }

    private record Stats(
            int successes,
            int insufficientStock,
            int conflicts,
            int otherFailures,
            int finalStock,
            long elapsedMs) {}
}
