package com.klmin.commerce.notificationservice;

/** 다시 처리해도 결과가 같은 메시지 오류. 재시도하지 않고 DLT 로 보존한다 */
class NonRetryableMessageException extends RuntimeException {

    NonRetryableMessageException(String message) {
        super(message);
    }
}
