package com.klmin.commerce.searchservice.product.application.port.in;

import java.time.Instant;

/**
 * product.cdc.products 의 변경 후 행 하나.
 *
 * @param version 원본 WAL 위치(헤더 {@code __lsn}). 같은 상품의 뒤 변경일수록 크다
 */
public record IndexProductCommand(long productId, String name, String description, long price, boolean onSale,
                                  Instant updatedAt, long version) {
}
