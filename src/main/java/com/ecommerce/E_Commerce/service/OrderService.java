package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.PlaceOrderRequest;
import com.ecommerce.E_Commerce.entity.Order;
import com.ecommerce.E_Commerce.messaging.OrderPlacedEvent;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final OrderCheckoutTransaction checkoutTransaction;
    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    public OrderService(OrderCheckoutTransaction checkoutTransaction,
                        RabbitTemplate rabbitTemplate) {
        this.checkoutTransaction = checkoutTransaction;
        this.rabbitTemplate = rabbitTemplate;
    }

    // NOT @Transactional on purpose. Each attempt below opens its own
    // transaction inside checkoutTransaction.execute(...). If we wrapped the
    // whole loop in @Transactional, an optimistic-lock failure would mark the
    // outer transaction rollback-only and the retry would corrupt JPA state.
    public Order placeOrder(PlaceOrderRequest request) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // checkoutTransaction.execute() is @Transactional with no outer
                // transaction, so its commit happens before this line returns.
                // Publishing here is therefore guaranteed publish-after-commit:
                // a rolled-back order never triggers invoice or notification.
                Order order = checkoutTransaction.execute(request);
                rabbitTemplate.convertAndSend(exchange, routingKey,
                        new OrderPlacedEvent(order.getId()));
                log.info("OrderPlaced event published for order #{}", order.getId());
                return order;
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
