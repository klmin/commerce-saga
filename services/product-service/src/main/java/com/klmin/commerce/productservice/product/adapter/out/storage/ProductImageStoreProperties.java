package com.klmin.commerce.productservice.product.adapter.out.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 이미지 저장소 (RustFS, S3 API, ADR 0021). 계정 기본값은 로컬 개발용(infra/docker-compose.yml), 운영은 Secret.
 * 타임아웃을 짧게 둔다: 저장소가 멈춰도 업로드·이미지 요청만 빨리 실패하고 다른 API 는 영향이 없게
 */
@ConfigurationProperties("product.images")
record ProductImageStoreProperties(String endpoint, String region, String bucket, String accessKey,
                                   String secretKey, Duration connectTimeout, Duration readTimeout,
                                   boolean seed) {
}
