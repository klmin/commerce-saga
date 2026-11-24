package com.klmin.commerce.productservice.product.application.port.in;

public record ChangeSaleStatusCommand(long productId, boolean onSale) {
}
