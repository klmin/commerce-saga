package com.klmin.commerce.productservice.product.application.port.in;

import java.util.List;

public record ProductAuditPageResult(List<ProductAuditResult> items, boolean hasNext) {
}
