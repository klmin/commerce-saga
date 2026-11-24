package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.common.web.CurrentUserId;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.DescribeProductCommand;
import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.ManageProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 관리자 상품 관리 (ADMIN 역할, SecurityConfig). Gateway 의 /api/admin/products/** 가 여기로 온다.
 * 가격을 바꾸면 이력에 바꾼 사람(JWT sub)과 시각이 남는다.
 */
@RestController
@RequestMapping("/admin/products")
class AdminProductController {

    private final ManageProductUseCase manageProductUseCase;
    private final GetProductUseCase getProductUseCase;

    AdminProductController(ManageProductUseCase manageProductUseCase, GetProductUseCase getProductUseCase) {
        this.manageProductUseCase = manageProductUseCase;
        this.getProductUseCase = getProductUseCase;
    }

    /** 판매 중지 상품까지 모두 (id 순) */
    @GetMapping
    ProductPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(GetProductUseCase.MAX_PAGE_SIZE) int size) {
        return ProductPageResponse.from(getProductUseCase.list(new ListProductsQuery(page, size, true)));
    }

    @PostMapping
    ResponseEntity<ProductResponse> register(@Valid @RequestBody ProductRequests.Register request) {
        ProductResult result = manageProductUseCase.register(
                new RegisterProductCommand(request.name(), request.description(), request.price()));
        return ResponseEntity.created(URI.create("/products/" + result.id())).body(ProductResponse.from(result));
    }

    @PutMapping("/{productId}")
    ProductResponse describe(@PathVariable @Positive long productId,
                             @Valid @RequestBody ProductRequests.Describe request) {
        return ProductResponse.from(manageProductUseCase.describe(
                new DescribeProductCommand(productId, request.name(), request.description())));
    }

    @PutMapping("/{productId}/price")
    ProductResponse changePrice(@CurrentUserId long adminId, @PathVariable @Positive long productId,
                                @Valid @RequestBody ProductRequests.ChangePrice request) {
        return ProductResponse.from(manageProductUseCase.changePrice(
                new ChangePriceCommand(productId, request.price(), adminId)));
    }

    @PutMapping("/{productId}/sale")
    ProductResponse changeSaleStatus(@PathVariable @Positive long productId,
                                     @Valid @RequestBody ProductRequests.ChangeSaleStatus request) {
        return ProductResponse.from(manageProductUseCase.changeSaleStatus(
                new ChangeSaleStatusCommand(productId, request.onSale())));
    }

    /** 가격 변경 이력 (최근부터) */
    @GetMapping("/{productId}/price-history")
    List<PriceChangeResponse> priceHistory(@PathVariable @Positive long productId) {
        return getProductUseCase.priceHistory(productId).stream().map(PriceChangeResponse::from).toList();
    }
}
