package com.klmin.commerce.searchservice.product.application.port.in;

import java.util.List;
import java.util.UUID;

public record ProductSearchResult(List<Item> items, long total, int page, int size) {

    /**
     * @param category  카테고리 코드 (색인에 없으면 null)
     * @param imagePath 대표 이미지 읽기 경로(product-service 기준, 화면이 {@code /api} 를 붙인다). 없으면 null
     */
    public record Item(UUID id, String name, String description, long price, boolean onSale, String category,
                       String imagePath) {

        public Item(UUID id, String name, String description, long price, boolean onSale) {
            this(id, name, description, price, onSale, null, null);
        }
    }
}
