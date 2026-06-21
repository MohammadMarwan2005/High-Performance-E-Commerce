package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.OrderItemRequest;
import com.ecommerce.E_Commerce.dto.PlaceOrderRequest;
import com.ecommerce.E_Commerce.entity.Order;
import com.ecommerce.E_Commerce.entity.OrderItem;
import com.ecommerce.E_Commerce.entity.OrderStatus;
import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.entity.User;
import com.ecommerce.E_Commerce.repository.OrderRepository;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import com.ecommerce.E_Commerce.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Full-order checkout using PESSIMISTIC_WRITE row locks (Req 7) — the
 * pessimistic twin of {@link OrderCheckoutTransaction}.
 *
 * <p>Each product row is locked with {@code SELECT ... FOR UPDATE} before its
 * stock is read and decremented, so concurrent checkouts of the same product
 * serialize on the lock. Because there is no optimistic conflict, this path
 * needs no retry — which is why {@link OrderService} calls it exactly once.
 */
@Service
public class PessimisticOrderCheckout {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public PessimisticOrderCheckout(UserRepository userRepository,
                                    ProductRepository productRepository,
                                    OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order execute(PlaceOrderRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));

        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .total(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;

        // Deadlock avoidance: always acquire row locks in a consistent order
        // (ascending product id). If two orders touch products {A,B}, both lock
        // A before B, so they can never hold-and-wait in a cycle.
        List<OrderItemRequest> orderedItems = request.items().stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .toList();

        for (OrderItemRequest line : orderedItems) {
            // Synchronization point: lock the row (SELECT ... FOR UPDATE).
            Product product = productRepository.findByIdForUpdate(line.productId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "product " + line.productId() + " not found"));

            if (product.getStockQuantity() < line.quantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "insufficient stock for product " + product.getId()
                                + " (available=" + product.getStockQuantity()
                                + ", requested=" + line.quantity() + ")");
            }

            product.setStockQuantity(product.getStockQuantity() - line.quantity());

            BigDecimal unitPrice = product.getPrice();
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .product(product)
                    .quantity(line.quantity())
                    .unitPrice(unitPrice)
                    .build();
            order.getItems().add(item);
            total = total.add(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
        }

        order.setTotal(total);
        return orderRepository.save(order);
    }
}
