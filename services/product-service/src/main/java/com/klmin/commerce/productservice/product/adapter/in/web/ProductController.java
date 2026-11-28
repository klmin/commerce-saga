package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.ProductImageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductImageUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 공개 상품 조회 (로그인 없이). 화면 목록·상세와 order-service 의 가격 확인(ids)이 쓴다 (ADR 0016).
 * 가격은 안내용이고 주문 때 order 가 이 API 로 다시 확인한다.
 */
@RestController
@RequestMapping("/products")
class ProductController {

    private final GetProductUseCase getProductUseCase;
    private final ProductImageUseCase productImageUseCase;

    ProductController(GetProductUseCase getProductUseCase, ProductImageUseCase productImageUseCase) {
        this.getProductUseCase = getProductUseCase;
        this.productImageUseCase = productImageUseCase;
    }

    /** 판매 중인 상품 목록 (id 순, 페이지) */
    @GetMapping(params = "!ids")
    ProductPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(GetProductUseCase.MAX_PAGE_SIZE) int size) {
        return ProductPageResponse.from(getProductUseCase.list(new ListProductsQuery(page, size, false)));
    }

    /** 여러 상품 한 번에 (?ids=uuid1,uuid2). 판매 중지 상품도 onSale=false 로 돌려준다. 없는 id 는 빠진다 */
    @GetMapping(params = "ids")
    ProductListResponse findByIds(
            @RequestParam @Size(min = 1, max = GetProductUseCase.MAX_IDS) List<@NotNull UUID> ids) {
        return new ProductListResponse(getProductUseCase.findByIds(ids).stream().map(ProductResponse::from).toList());
    }

    /** 카테고리 고정 목록 (정해 둔 순서, ADR 0021) */
    @GetMapping("/categories")
    List<CategoryResponse> categories() {
        return getProductUseCase.categories().stream().map(CategoryResponse::from).toList();
    }

    /**
     * 상품 이미지 (공개). key 마다 내용이 바뀌지 않아 길게 캐시한다. 이 출처에서 열려도 스크립트가 돌지 않게
     * 응답을 sandbox 로 두고(시드 SVG), 형식 추측(nosniff)을 막는다
     */
    @GetMapping("/{productId}/images/{fileName}")
    ResponseEntity<byte[]> image(@PathVariable String productId, @PathVariable String fileName) {
        ProductImageResult image = productImageUseCase.image(productId, fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(image.data());
    }

    @GetMapping("/{productId}")
    ProductResponse get(@PathVariable UUID productId) {
        return ProductResponse.from(getProductUseCase.get(productId));
    }
}
