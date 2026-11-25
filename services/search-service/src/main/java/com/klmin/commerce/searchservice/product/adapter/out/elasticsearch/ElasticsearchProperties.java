package com.klmin.commerce.searchservice.product.adapter.out.elasticsearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param uri            Elasticsearch 주소
 * @param index          별칭 이름. 실제 색인은 {@code <index>-v<n>} 이고 다시 색인할 때 별칭을 옮긴다 (ADR 0010)
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 타임아웃
 */
@ConfigurationProperties("search.elasticsearch")
record ElasticsearchProperties(String uri, String index, Duration connectTimeout, Duration readTimeout) {
}
