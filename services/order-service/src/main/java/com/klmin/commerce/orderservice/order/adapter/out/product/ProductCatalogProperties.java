package com.klmin.commerce.orderservice.order.adapter.out.product;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * product-service 조회 (ADR 0016).
 *
 * @param baseUrl Gateway 를 거치지 않는 클러스터 안 주소
 * @param timeout 연결·응답 각각의 한도. 넘으면 주문을 만들지 않고 503 (재시도 없음)
 */
@ConfigurationProperties("order.product-catalog")
record ProductCatalogProperties(String baseUrl, Duration timeout) {
}
