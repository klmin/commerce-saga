package com.klmin.commerce.searchservice.common.error;

/**
 * 다시 처리해도 결과가 같은 메시지 오류 (본문이 비었거나 필수 값이 없는 등).
 * 리스너 에러 핸들러가 재시도하지 않고 건너뛴다. 일시 오류(DB 장애 등)와 구분하려고 쓴다.
 */
public class NonRetryableMessageException extends RuntimeException {

    public NonRetryableMessageException(String message) {
        super(message);
    }
}
