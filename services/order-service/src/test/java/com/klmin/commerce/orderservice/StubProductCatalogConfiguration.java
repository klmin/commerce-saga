package com.klmin.commerce.orderservice;

import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트용 상품 가격: 모든 상품이 판매 중이고 1,000원 (product-service 없이 주문 흐름을 본다) */
@TestConfiguration(proxyBeanMethods = false)
public class StubProductCatalogConfiguration {

    public static final long PRICE = 1_000L;

    @Bean
    @Primary
    ProductCatalogPort stubProductCatalog() {
        return ids -> ids.stream().map(id -> new ProductCatalogPort.CatalogProduct(id, PRICE, true)).toList();
    }
}
