package com.klmin.commerce.productservice.product.application.port.in;

/** changedBy = 요청한 관리자(JWT sub) */
public record ChangePriceCommand(long productId, long price, long changedBy) {
}
