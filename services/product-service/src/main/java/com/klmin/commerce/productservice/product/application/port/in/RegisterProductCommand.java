package com.klmin.commerce.productservice.product.application.port.in;

public record RegisterProductCommand(String name, String description, long price) {
}
