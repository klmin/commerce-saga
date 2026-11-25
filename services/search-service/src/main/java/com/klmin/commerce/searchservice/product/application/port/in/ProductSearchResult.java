package com.klmin.commerce.searchservice.product.application.port.in;

import java.util.List;

public record ProductSearchResult(List<Item> items, long total, int page, int size) {

    public record Item(long id, String name, String description, long price, boolean onSale) {
    }
}
