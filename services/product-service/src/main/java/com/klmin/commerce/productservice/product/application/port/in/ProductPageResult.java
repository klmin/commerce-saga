package com.klmin.commerce.productservice.product.application.port.in;

import java.util.List;

public record ProductPageResult(List<ProductResult> items, boolean hasNext) {
}
