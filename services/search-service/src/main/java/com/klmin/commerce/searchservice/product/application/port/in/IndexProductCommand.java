package com.klmin.commerce.searchservice.product.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * product.cdc.products 의 변경 후 행 하나.
 *
 * @param version  원본 WAL 위치(헤더 {@code __lsn}). 같은 상품의 뒤 변경일수록 크다
 * @param category 카테고리 코드 (열 {@code category_code}, ADR 0021). 열을 추가하기 전의 메시지면 null
 * @param imageKey 대표 이미지 key (열 {@code image_key}). 없으면 null
 */
public record IndexProductCommand(UUID productId, String name, String description, long price, boolean onSale,
                                  Instant updatedAt, long version, String category, String imageKey) {

    /** 카테고리·이미지 열이 없는 메시지 */
    public IndexProductCommand(UUID productId, String name, String description, long price, boolean onSale,
                               Instant updatedAt, long version) {
        this(productId, name, description, price, onSale, updatedAt, version, null, null);
    }
}
