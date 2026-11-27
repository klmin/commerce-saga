package com.klmin.commerce.searchservice.product.application.port.in;

import java.util.List;
import java.util.UUID;

public record ProductSearchResult(List<Item> items, long total, int page, int size) {

    public record Item(UUID id, String name, String description, long price, boolean onSale) {
    }
}
