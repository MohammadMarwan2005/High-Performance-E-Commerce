package com.ecommerce.E_Commerce.messaging;

import java.io.Serializable;

public record OrderPlacedEvent(Long orderId) implements Serializable {}
