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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderCheckoutTransaction {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderCheckoutTransaction(
            UserRepository userRepository,
            ProductRepository productRepository,
            OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    // One checkout attempt inside a single ACID transaction.
    // Each call from OrderService runs in its OWN fresh transaction — that is
    // intentional so the retry loop can re-read the latest Product.version.
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

        for (OrderItemRequest line : request.items()) {
            // Synchronization point #1: load Product fresh in THIS transaction.
            // The Product.version observed here is what Hibernate will compare
            // against the row at UPDATE time.
            Product product = productRepository.findById(line.productId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "product " + line.productId() + " not found"));

            if (product.getStockQuantity() < line.quantity()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "insufficient stock for product " + product.getId()
                                + " (available=" + product.getStockQuantity()
                                + ", requested=" + line.quantity() + ")");
            }

            // Synchronization point #2: stock decrement.
            // On flush, Hibernate issues:
            //   UPDATE products SET stock_quantity=?, version=version+1
            //   WHERE id=? AND version=?
            // If another tx has bumped the version between our load and our
            // flush, the UPDATE matches 0 rows and Hibernate raises
            // ObjectOptimisticLockingFailureException. The retry loop in
            // OrderService handles that.
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
