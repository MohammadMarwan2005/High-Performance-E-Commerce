package com.ecommerce.E_Commerce.repository;

import com.ecommerce.E_Commerce.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
