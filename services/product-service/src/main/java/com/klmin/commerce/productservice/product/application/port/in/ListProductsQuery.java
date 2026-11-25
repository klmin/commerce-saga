package com.klmin.commerce.productservice.product.application.port.in;

/** page 는 0부터. includeStopped 는 관리자 목록만 true */
public record ListProductsQuery(int page, int size, boolean includeStopped) {
}
