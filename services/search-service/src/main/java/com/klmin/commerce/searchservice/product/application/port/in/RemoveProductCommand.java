package com.klmin.commerce.searchservice.product.application.port.in;

import java.util.UUID;

/**
 * 삭제(tombstone). 상품은 지우지 않고 판매 중지하지만(4-1a), 행이 지워지면 문서도 지운다.
 *
 * @param version 원본 WAL 위치. tombstone 에 헤더가 없으면 null → 영구 삭제 표시 (UUIDv7 상품 id 는 다시 쓰이지 않는다)
 */
public record RemoveProductCommand(UUID productId, Long version) {
}
