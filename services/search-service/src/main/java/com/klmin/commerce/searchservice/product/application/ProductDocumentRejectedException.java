package com.klmin.commerce.searchservice.product.application;

import com.klmin.commerce.searchservice.common.error.NonRetryableMessageException;

/**
 * 검색 저장소가 문서를 거절했다(매핑과 맞지 않는 값 등). 다시 보내도 같으므로 재시도하지 않고 원본 메시지를 DLT 로 보존한다.
 * 저장소 장애(연결 실패·5xx)는 이 예외가 아니다 → 복구될 때까지 제자리 재시도 (ADR 0012)
 */
public class ProductDocumentRejectedException extends NonRetryableMessageException {

    public ProductDocumentRejectedException(String message) {
        super(message);
    }
}
