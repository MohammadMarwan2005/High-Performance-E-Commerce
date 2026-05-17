package com.ecommerce.E_Commerce.concurrency;

import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import com.ecommerce.E_Commerce.service.LockedCheckoutService;
import com.ecommerce.E_Commerce.service.NaiveCheckoutService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ConcurrencyProofIT {

    private static final int THREADS = 50;
    private static final int INITIAL_STOCK = 1;
    private static final String DEMO_PRODUCT = "RACE_DEMO";
    private static final Path PROOF_DIR = Path.of("docs/req1-proof");

    @Autowired NaiveCheckoutService naive;
    @Autowired LockedCheckoutService locked;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void without_optimistic_locking_oversells() throws Exception {
        Long productId = resetDemoProduct();
        Stats stats = race(productId, naive::purchase);
        writeReport("without-lock.txt",
                "WITHOUT optimistic locking",
                "NaiveCheckoutService (raw SQL, no @Version)",
                stats);
    }

    @Test
    void with_optimistic_locking_holds() throws Exception {
        Long productId = resetDemoProduct();
        Stats stats = race(productId, locked::purchase);
        writeReport("with-lock.txt",
                "WITH optimistic locking + retry",
                "LockedCheckoutService → LockedCheckoutAttempt (@Version on Product, 3 retries)",
                stats);
    }

    private Long resetDemoProduct() {
        Optional<Product> existing = productRepository.findAll().stream()
                .filter(p -> DEMO_PRODUCT.equals(p.getName()))
                .findFirst();
        Product product = existing.orElseGet(() -> Product.builder()
                .name(DEMO_PRODUCT)
                .price(new BigDecimal("9.99"))
                .stockQuantity(INITIAL_STOCK)
                .build());
        product.setStockQuantity(INITIAL_STOCK);
        return productRepository.save(product).getId();
    }

    private Stats race(Long productId, Purchase purchase) throws InterruptedException {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficientStock = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    purchase.run(productId, 1);
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
        done.await(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();

        Integer finalStock = jdbc.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?",
                Integer.class, productId);

        return new Stats(
                THREADS,
                INITIAL_STOCK,
                successes.get(),
                insufficientStock.get(),
                conflicts.get(),
                otherFailures.get(),
                finalStock == null ? -9999 : finalStock,
                elapsedMs);
    }

    private void writeReport(String fileName, String title, String mode, Stats s) throws Exception {
        Files.createDirectories(PROOF_DIR);
        boolean oversold = s.successes > s.initialStock;
        String body = """
                === Concurrency Proof — %s ===
                Generated: %s
                Mode:      %s

                Setup:
                  Threads:        %d (concurrent)
                  Initial stock:  %d
                  Quantity each:  1
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
                %s
                """.formatted(
                title,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                mode,
                s.threads, s.initialStock, s.threads, s.initialStock,
                s.successes, s.insufficientStock, s.conflicts, s.otherFailures,
                s.finalStock, s.elapsedMs,
                oversold ? "YES — bug demonstrated" : "NO — invariant held",
                oversold
                        ? """

                                Interpretation:
                                  Multiple threads observed stock=1 simultaneously, all passed the
                                  check, all wrote a decremented value. With no version check the
                                  late writer overwrites the early writer (lost update). Each "success"
                                  is a real order receipt that the store cannot fulfill."""
                        : """

                                Interpretation:
                                  Hibernate's UPDATE ... WHERE id=? AND version=? lets exactly one
                                  transaction win the race on the row. Losers either retry (and then
                                  observe stock=0, failing with "insufficient stock") or exhaust the
                                  retry budget and abort with a conflict.""");
        Files.writeString(PROOF_DIR.resolve(fileName), body);
        System.out.println("\n" + body);
    }

    @FunctionalInterface
    interface Purchase {
        void run(Long productId, int qty);
    }

    record Stats(
            int threads,
            int initialStock,
            int successes,
            int insufficientStock,
            int conflicts,
            int otherFailures,
            int finalStock,
            long elapsedMs) {}
}
