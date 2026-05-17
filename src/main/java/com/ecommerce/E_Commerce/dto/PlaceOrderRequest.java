package com.ecommerce.E_Commerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlaceOrderRequest(
        @NotNull Long userId,
        @NotEmpty @Valid List<OrderItemRequest> items
) {
}
