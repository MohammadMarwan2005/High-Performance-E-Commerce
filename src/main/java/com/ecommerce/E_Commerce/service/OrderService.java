package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.PlaceOrderRequest;
import com.ecommerce.E_Commerce.entity.Order;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final OrderCheckoutTransaction checkoutTransaction;

    public OrderService(OrderCheckoutTransaction checkoutTransaction) {
        this.checkoutTransaction = checkoutTransaction;
    }

    // NOT @Transactional on purpose. Each attempt below opens its own
    // transaction inside checkoutTransaction.execute(...). If we wrapped the
    // whole loop in @Transactional, an optimistic-lock failure would mark the
    // outer transaction rollback-only and the retry would corrupt JPA state.
    public Order placeOrder(PlaceOrderRequest request) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return checkoutTransaction.execute(request);
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                log.warn("optimistic lock conflict on placeOrder (attempt {}/{}): {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "order could not be placed due to concurrent stock updates; please retry");
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
