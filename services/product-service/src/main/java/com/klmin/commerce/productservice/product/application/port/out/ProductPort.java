package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductId;

import java.util.Optional;

public interface ProductPort {

    /** 새 상품이면 id 를 매겨 돌려준다 */
    Product save(Product product);

    /** 쓰기 전에 행을 잠근다 (동시 가격 변경이 이력을 엇갈리게 남기지 않게) */
    Optional<Product> findByIdForUpdate(ProductId productId);
}
