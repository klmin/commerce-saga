package com.klmin.commerce.productservice.product.application.port.in;

import java.util.UUID;

/** changedBy = 요청한 관리자(JWT sub), 관리 이력에 남는다. categoryCode 가 null 이면 카테고리는 그대로 (ADR 0021) */
public record DescribeProductCommand(UUID productId, String name, String description, String categoryCode,
                                     UUID changedBy) {

    public DescribeProductCommand(UUID productId, String name, String description, UUID changedBy) {
        this(productId, name, description, null, changedBy);
    }
}
