package com.klmin.commerce.pointservice.common.error;

/**
 * 저장된 데이터끼리 어긋나 규칙을 지킬 수 없는 경우 (예: 사용 기록보다 잔액 환불 결과가 범위를 벗어남).
 * 재시도해도 같은 결과라 리스너 에러 핸들러가 재시도하지 않고 DLT 로 보존한다. 운영자가 원인을 확인해야 한다.
 */
public class InvariantViolationException extends RuntimeException {

    public InvariantViolationException(String message) {
        super(message);
    }
}
