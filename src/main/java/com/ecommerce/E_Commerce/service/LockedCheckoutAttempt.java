package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One ACID attempt of a version-checked stock decrement. */
@Service
public class LockedCheckoutAttempt {

    private final ProductRepository productRepository;

    public LockedCheckoutAttempt(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public void execute(Long productId, int quantity) {
        // Synchronization point: load Product fresh in this tx, capturing its
        // current @Version.
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalStateException("product not found"));

        if (product.getStockQuantity() < quantity) {
            throw new IllegalStateException("insufficient stock");
        }

        // On flush Hibernate emits:
        //   UPDATE products SET stock_quantity=?, version=version+1
        //   WHERE id=? AND version=?
        // If another tx changed this row since we loaded it, the UPDATE
        // matches 0 rows and ObjectOptimisticLockingFailureException is
        // thrown — the retry loop in LockedCheckoutService handles it.
        product.setStockQuantity(product.getStockQuantity() - quantity);
    }
}
