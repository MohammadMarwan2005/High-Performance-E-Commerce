package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.PlaceOrderRequest;
import com.ecommerce.E_Commerce.entity.Order;
import com.ecommerce.E_Commerce.messaging.OrderPlacedEvent;
import com.ecommerce.E_Commerce.monitoring.Timed;
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
    private final PessimisticOrderCheckout pessimisticCheckout;
    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    // Req 7 selector: "optimistic" (default, @Version + retry) or "pessimistic"
    // (SELECT ... FOR UPDATE). Switch via app.checkout.strategy without a rebuild.
    @Value("${app.checkout.strategy:optimistic}")
    private String strategy;

    public OrderService(OrderCheckoutTransaction checkoutTransaction,
                        PessimisticOrderCheckout pessimisticCheckout,
                        RabbitTemplate rabbitTemplate) {
        this.checkoutTransaction = checkoutTransaction;
        this.pessimisticCheckout = pessimisticCheckout;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Timed("OrderService.placeOrder")
    public Order placeOrder(PlaceOrderRequest request) {
        Order order = "pessimistic".equalsIgnoreCase(strategy)
                ? placePessimistic(request)
                : placeOptimistic(request);

        // Publish-after-commit (both paths commit inside their execute() call
        // before returning here): a rolled-back order never triggers messaging.
        rabbitTemplate.convertAndSend(exchange, routingKey, new OrderPlacedEvent(order.getId()));
        log.info("OrderPlaced event published for order #{} (strategy={})", order.getId(), strategy);
        return order;
    }

    // Optimistic path (Phase 1): bounded retry on version conflict.
    //
    // NOT wrapped in @Transactional on purpose — each attempt opens its own
    // transaction inside checkoutTransaction.execute(...). A single outer
    // transaction would be marked rollback-only on the first conflict, and the
    // retry would corrupt JPA state.
    private Order placeOptimistic(PlaceOrderRequest request) {
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

    // Pessimistic path (Req 7): the row is locked with SELECT ... FOR UPDATE, so
    // concurrent checkouts serialize on the lock. No conflict, no retry — one call.
    private Order placePessimistic(PlaceOrderRequest request) {
        return pessimisticCheckout.execute(request);
    }
}
