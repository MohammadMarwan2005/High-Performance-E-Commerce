package com.ecommerce.E_Commerce.repository;

import com.ecommerce.E_Commerce.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
