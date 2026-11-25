package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공개 상품 조회 (로그인 없이). 화면 목록·상세와 order-service 의 가격 확인(ids)이 쓴다 (ADR 0016).
 * 가격은 안내용이고 주문 때 order 가 이 API 로 다시 확인한다.
 */
@RestController
@RequestMapping("/products")
class ProductController {

    private final GetProductUseCase getProductUseCase;

    ProductController(GetProductUseCase getProductUseCase) {
        this.getProductUseCase = getProductUseCase;
    }

    /** 판매 중인 상품 목록 (id 순, 페이지) */
    @GetMapping(params = "!ids")
    ProductPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(GetProductUseCase.MAX_PAGE_SIZE) int size) {
        return ProductPageResponse.from(getProductUseCase.list(new ListProductsQuery(page, size, false)));
    }

    /** 여러 상품 한 번에 (?ids=1,2,3). 판매 중지 상품도 onSale=false 로 돌려준다. 없는 id 는 빠진다 */
    @GetMapping(params = "ids")
    ProductListResponse findByIds(
            @RequestParam @Size(min = 1, max = GetProductUseCase.MAX_IDS) List<@Positive Long> ids) {
        return new ProductListResponse(getProductUseCase.findByIds(ids).stream().map(ProductResponse::from).toList());
    }

    @GetMapping("/{productId}")
    ProductResponse get(@PathVariable @Positive long productId) {
        return ProductResponse.from(getProductUseCase.get(productId));
    }
}
