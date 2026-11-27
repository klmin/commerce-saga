package com.klmin.commerce.productservice.product.application.port.in;

import java.time.Instant;
import java.util.UUID;

public record PriceChangeResult(long oldPrice, long newPrice, UUID changedBy, Instant changedAt) {
}
