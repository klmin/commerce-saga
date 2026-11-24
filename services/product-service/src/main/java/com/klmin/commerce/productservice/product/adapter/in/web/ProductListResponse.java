package com.klmin.commerce.productservice.product.adapter.in.web;

import java.util.List;

/** 여러 건 조회(ids) 응답. 없는 id 는 빠진다 */
record ProductListResponse(List<ProductResponse> items) {
}
