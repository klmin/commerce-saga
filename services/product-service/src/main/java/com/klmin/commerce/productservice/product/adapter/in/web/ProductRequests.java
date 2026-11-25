package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.domain.Money;
import com.klmin.commerce.productservice.product.domain.Product;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 관리자 요청 본문 */
final class ProductRequests {

    private ProductRequests() {
    }

    record Register(
            @NotBlank @Size(max = Product.MAX_NAME_LENGTH) String name,
            @Size(max = Product.MAX_DESCRIPTION_LENGTH) String description,
            @NotNull @Positive @Max(Money.MAX) Long price) {
    }

    record Describe(
            @NotBlank @Size(max = Product.MAX_NAME_LENGTH) String name,
            @Size(max = Product.MAX_DESCRIPTION_LENGTH) String description) {
    }

    record ChangePrice(@NotNull @Positive @Max(Money.MAX) Long price) {
    }

    record ChangeSaleStatus(@NotNull Boolean onSale) {
    }
}
