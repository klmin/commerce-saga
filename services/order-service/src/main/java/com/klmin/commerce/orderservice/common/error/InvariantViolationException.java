package com.klmin.commerce.orderservice.common.error;

/**
 * 저장된 데이터끼리 어긋나 규칙을 지킬 수 없는 경우 (예: Saga 는 있는데 주문이 없음).
 * 재시도해도 같은 결과라 리스너 에러 핸들러가 재시도하지 않고 DLT 로 보존한다. 운영자가 원인을 확인해야 한다.
 */
public class InvariantViolationException extends RuntimeException {

    public InvariantViolationException(String message) {
        super(message);
    }
}
