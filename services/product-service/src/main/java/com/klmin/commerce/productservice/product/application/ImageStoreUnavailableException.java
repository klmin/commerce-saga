package com.klmin.commerce.productservice.product.application;

import com.klmin.commerce.productservice.common.error.ServiceUnavailableException;

/** 이미지 저장소(RustFS)에 쓰거나 읽지 못함 (503). 상품·주문은 영향이 없다 (ADR 0021) */
public class ImageStoreUnavailableException extends ServiceUnavailableException {

    public ImageStoreUnavailableException(Throwable cause) {
        super("이미지 저장소에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.", cause);
    }
}
