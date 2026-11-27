package com.klmin.commerce.searchservice.product.adapter.in.web;

import com.klmin.commerce.searchservice.product.application.port.in.ProductSearchResult;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsQuery;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 상품 검색 (공개, Gateway {@code GET /api/search/products}). 결과 가격은 안내용이다: 주문 때 order-service 가
 * product-service 의 지금 가격으로 다시 확인한다 (ADR 0016). 검색 저장소 장애면 503
 */
@RestController
@RequestMapping("/search")
class SearchController {

    private final SearchProductsUseCase searchProductsUseCase;

    SearchController(SearchProductsUseCase searchProductsUseCase) {
        this.searchProductsUseCase = searchProductsUseCase;
    }

    @GetMapping("/products")
    SearchResponse searchProducts(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) Long minPrice,
                                  @RequestParam(required = false) Long maxPrice,
                                  @RequestParam(defaultValue = "true") boolean onSaleOnly,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        ProductSearchResult result = searchProductsUseCase.search(
                new SearchProductsQuery(q, minPrice, maxPrice, onSaleOnly, sort, page, size));
        return new SearchResponse(
                result.items().stream()
                        .map(i -> new SearchResponse.Item(i.id(), i.name(), i.description(), i.price(), i.onSale()))
                        .toList(),
                result.total(), result.page(), result.size());
    }

    record SearchResponse(List<Item> items, long total, int page, int size) {

        record Item(UUID id, String name, String description, long price, boolean onSale) {
        }
    }
}
