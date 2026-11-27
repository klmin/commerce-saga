package com.klmin.commerce.inventoryservice.common.error;

/**
 * 지금 상태로는 요청을 받아들일 수 없음 (409). 입력 자체는 맞지만 저장된 값과 맞지 않는 경우
 * (예: 가용 재고보다 많이 차감). 상태가 바뀌면 같은 요청이 성공할 수 있다는 점이 {@link DomainValidationException}(400)과 다르다
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
