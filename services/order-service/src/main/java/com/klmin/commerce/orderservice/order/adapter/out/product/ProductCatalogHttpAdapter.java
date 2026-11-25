package com.klmin.commerce.orderservice.order.adapter.out.product;

import com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * product-service 의 공개 조회 {@code GET /products?ids=} 를 동기로 부른다 (ADR 0016).
 * 상태를 바꾸지 않는 조회라 실패·타임아웃은 "결과 모름"이 아니라 그냥 실패(503)다. 재시도하지 않는다.
 * RestClient.Builder 를 써서 trace 문맥(traceparent)이 product 로 이어진다 (ADR 0013).
 */
@Component
@EnableConfigurationProperties(ProductCatalogProperties.class)
class ProductCatalogHttpAdapter implements ProductCatalogPort {

    private final RestClient client;

    ProductCatalogHttpAdapter(RestClient.Builder builder, ProductCatalogProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.client = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public List<CatalogProduct> findByIds(Collection<Long> productIds) {
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        ProductsResponse response;
        try {
            response = client.get()
                    .uri(uri -> uri.path("/products").queryParam("ids", ids).build())
                    .retrieve()
                    .body(ProductsResponse.class);
        } catch (RestClientException e) {
            throw new ProductCatalogUnavailableException(e);
        }
        if (response == null || response.items() == null) {
            throw new ProductCatalogUnavailableException(new IllegalStateException("empty product response"));
        }
        return response.items().stream()
                .map(p -> new CatalogProduct(p.id(), p.price(), p.onSale()))
                .toList();
    }

    record ProductsResponse(List<Item> items) {
    }

    record Item(long id, long price, boolean onSale) {
    }
}
