package com.klmin.commerce.productservice.product.application.port.in;

/** 관리자(ADMIN)의 상품 등록·수정·가격 변경·판매 상태 변경 */
public interface ManageProductUseCase {

    ProductResult register(RegisterProductCommand command);

    ProductResult describe(DescribeProductCommand command);

    /** 가격이 바뀌면 이력(product_price_history)을 같은 트랜잭션에 남긴다 */
    ProductResult changePrice(ChangePriceCommand command);

    ProductResult changeSaleStatus(ChangeSaleStatusCommand command);
}
