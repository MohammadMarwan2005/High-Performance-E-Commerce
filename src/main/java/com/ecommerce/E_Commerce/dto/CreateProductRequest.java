package com.ecommerce.E_Commerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank String name,
        @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity
) {
}
