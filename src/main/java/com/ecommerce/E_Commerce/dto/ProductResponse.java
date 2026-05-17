package com.ecommerce.E_Commerce.dto;

import com.ecommerce.E_Commerce.entity.Product;
import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        Integer stockQuantity,
        Long version
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getVersion()
        );
    }
}
