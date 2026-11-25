package com.klmin.commerce.orderservice.order.application;

import com.klmin.commerce.orderservice.common.error.ServiceUnavailableException;

/** product-service 가 응답하지 않거나 오류 (503). 주문은 만들지 않았다 */
public class ProductCatalogUnavailableException extends ServiceUnavailableException {

    public ProductCatalogUnavailableException(Throwable cause) {
        super("상품 정보를 확인할 수 없어 지금은 주문할 수 없습니다. 잠시 뒤 다시 시도해 주세요.", cause);
    }
}
