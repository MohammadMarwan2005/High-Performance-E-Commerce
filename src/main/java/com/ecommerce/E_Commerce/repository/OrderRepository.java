package com.ecommerce.E_Commerce.repository;

import com.ecommerce.E_Commerce.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
