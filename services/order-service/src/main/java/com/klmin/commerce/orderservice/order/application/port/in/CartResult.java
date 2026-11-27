package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * @param pricesAvailable false 면 product-service 를 읽지 못해 name·currentPrice·onSale 이 비어 있다
 */
public record CartResult(List<Item> items, boolean pricesAvailable) {

    /**
     * @param addedPrice   담을 때 본 가격
     * @param currentPrice 지금 가격 (모르면 null). addedPrice 와 다르면 화면이 "가격이 바뀜"을 보여 준다
     * @param onSale       판매 중인지 (모르면 null, 상품이 없어졌으면 false)
     */
    public record Item(UUID productId, String name, int quantity, long addedPrice, Long currentPrice,
                       Boolean onSale) {

        public boolean priceChanged() {
            return currentPrice != null && currentPrice != addedPrice;
        }
    }
}
