package com.ecommerce.E_Commerce.dto;

import java.math.BigDecimal;

public record SalesSummaryDto(
        Long productId,
        String productName,
        Long totalQuantity,
        BigDecimal totalRevenue) {}
