package com.klmin.commerce.productservice.product.application.port.in;

import java.util.UUID;

/**
 * changedBy = 요청한 관리자(JWT sub), 관리 이력에 남는다.
 * categoryCode 가 null 이면 기타(etc): 카테고리 전의 클라이언트도 등록할 수 있게 (ADR 0021)
 */
public record RegisterProductCommand(String name, String description, long price, String categoryCode,
                                     UUID changedBy) {

    public RegisterProductCommand(String name, String description, long price, UUID changedBy) {
        this(name, description, price, null, changedBy);
    }
}
