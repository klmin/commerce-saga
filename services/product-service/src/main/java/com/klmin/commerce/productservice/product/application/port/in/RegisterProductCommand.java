package com.klmin.commerce.productservice.product.application.port.in;

/** changedBy = 요청한 관리자(JWT sub), 관리 이력에 남는다 */
public record RegisterProductCommand(String name, String description, long price, long changedBy) {
}
