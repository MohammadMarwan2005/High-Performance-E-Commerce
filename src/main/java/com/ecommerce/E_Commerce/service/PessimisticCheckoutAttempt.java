package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One ACID stock decrement guarded by a PESSIMISTIC_WRITE lock (Req 7).
 *
 * <p>Unlike {@link LockedCheckoutAttempt} (optimistic / {@code @Version}), this
 * acquires a row lock up front via {@code SELECT ... FOR UPDATE}. Concurrent
 * callers serialize on the lock instead of racing and retrying — so there is no
 * retry loop here: a caller either waits for the lock and then succeeds, or
 * fails on insufficient stock.
 */
@Service
public class PessimisticCheckoutAttempt {

    private final ProductRepository productRepository;

    public PessimisticCheckoutAttempt(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public void execute(Long productId, int quantity) {
        // Synchronization point: SELECT ... FOR UPDATE. The row is locked here;
        // any other transaction wanting the same row blocks until we commit.
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalStateException("product not found"));

        if (product.getStockQuantity() < quantity) {
            throw new IllegalStateException("insufficient stock");
        }

        // Safe: we hold the lock, so no one else can have changed the row since
        // our read. No version check, no conflict, no retry.
        product.setStockQuantity(product.getStockQuantity() - quantity);
    }
}
