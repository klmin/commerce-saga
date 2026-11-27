package com.klmin.commerce.productservice.product.application.port.in;

import java.util.UUID;

/** changedBy = 요청한 관리자(JWT sub) */
public record ChangePriceCommand(UUID productId, long price, long changedBy) {
}
