package com.klmin.commerce.productservice.product.application.port.in;

import java.util.UUID;

/** data = 올린 파일 내용 (형식·크기는 서비스가 검사). changedBy = 요청한 관리자(JWT sub) */
public record ChangeProductImageCommand(UUID productId, byte[] data, UUID changedBy) {
}
