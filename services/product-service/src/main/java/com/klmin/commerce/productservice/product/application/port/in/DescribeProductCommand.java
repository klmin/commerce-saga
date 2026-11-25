package com.klmin.commerce.productservice.product.application.port.in;

public record DescribeProductCommand(long productId, String name, String description) {
}
