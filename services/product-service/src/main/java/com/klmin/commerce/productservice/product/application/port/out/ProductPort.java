package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductId;

import java.util.Optional;

public interface ProductPort {

    /** 새 상품 (id 는 앱이 이미 정했다) */
    Product insert(Product product);

    /** 이미 있는 상품의 변경 */
    Product save(Product product);

    /** 쓰기 전에 행을 잠근다 (동시 가격 변경이 이력을 엇갈리게 남기지 않게) */
    Optional<Product> findByIdForUpdate(ProductId productId);
}
