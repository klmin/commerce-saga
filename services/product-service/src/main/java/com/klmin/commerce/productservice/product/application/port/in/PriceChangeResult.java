package com.klmin.commerce.productservice.product.application.port.in;

import java.time.Instant;

public record PriceChangeResult(long oldPrice, long newPrice, long changedBy, Instant changedAt) {
}
