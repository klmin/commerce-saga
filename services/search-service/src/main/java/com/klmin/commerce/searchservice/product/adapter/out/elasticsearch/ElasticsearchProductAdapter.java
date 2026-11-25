package com.klmin.commerce.searchservice.product.adapter.out.elasticsearch;

import com.klmin.commerce.searchservice.product.application.ProductDocumentRejectedException;
import com.klmin.commerce.searchservice.product.application.SearchStoreUnavailableException;
import com.klmin.commerce.searchservice.product.application.port.out.ProductIndexPort;
import com.klmin.commerce.searchservice.product.application.port.out.ProductSearchPort;
import com.klmin.commerce.searchservice.product.domain.SearchCriteria;
import com.klmin.commerce.searchservice.product.domain.SearchPage;
import com.klmin.commerce.searchservice.product.domain.SearchableProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 색인·검색 (ADR 0010). 공식 Java 클라이언트 대신 REST 를 직접 부른다: 쓰는 API 가 문서 쓰기·삭제·검색·
 * 색인 만들기뿐이고, OpenSearch 로 바꿀 때 클라이언트를 바꾸지 않아도 된다.
 * <ul>
 *   <li>문서 id = 상품 id, {@code version_type=external} 로 원본 LSN 을 버전으로 쓴다. 저장된 버전 이하면 409 → 건너뜀
 *       (재전달·순서 뒤바뀜이 색인을 되돌리지 못한다)</li>
 *   <li>별칭 {@code products} 가 없으면 {@code products-v1} 을 만들고 별칭을 건다(처음 한 번, 여러 인스턴스가 겹쳐도 됨)</li>
 *   <li>연결 실패·5xx 는 예외 그대로(색인은 리스너가 제자리 재시도) 또는 {@link SearchStoreUnavailableException}(검색 503).
 *       문서 거절(400)은 {@link ProductDocumentRejectedException} → DLT</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(ElasticsearchProperties.class)
class ElasticsearchProductAdapter implements ProductIndexPort, ProductSearchPort {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchProductAdapter.class);

    // 한글 상품명 부분 검색: 1~2글자 ngram (형태소 분석 nori 는 플러그인 이미지가 필요해 쓰지 않음, ADR 0010)
    private static final String INDEX_DEFINITION = """
            {
              "settings": {
                "index": { "number_of_shards": 1, "number_of_replicas": 0, "max_ngram_diff": 1 },
                "analysis": {
                  "tokenizer": {
                    "ngram_1_2": { "type": "ngram", "min_gram": 1, "max_gram": 2, "token_chars": ["letter", "digit"] }
                  },
                  "analyzer": {
                    "ngram": { "type": "custom", "tokenizer": "ngram_1_2", "filter": ["lowercase"] }
                  }
                }
              },
              "mappings": {
                "dynamic": "strict",
                "properties": {
                  "id": { "type": "long" },
                  "name": { "type": "text", "analyzer": "ngram", "fields": { "keyword": { "type": "keyword" } } },
                  "description": { "type": "text", "analyzer": "ngram" },
                  "price": { "type": "long" },
                  "onSale": { "type": "boolean" },
                  "updatedAt": { "type": "date" },
                  "deleted": { "type": "boolean" }
                }
              }
            }
            """;

    /** 버전 없는 tombstone 의 삭제 표시 버전. LSN 이 닿지 않는 큰 값(ES 외부 버전 상한 아래) */
    static final long DELETED_FOREVER_VERSION = 9_000_000_000_000_000_000L;

    private final RestClient client;
    private final JsonMapper json;
    private final String alias;
    private volatile boolean indexReady;

    ElasticsearchProductAdapter(RestClient.Builder builder, ElasticsearchProperties properties, JsonMapper json) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder.clone().baseUrl(properties.uri()).requestFactory(requestFactory).build();
        this.json = json;
        this.alias = properties.index();
    }

    @Override
    public boolean save(SearchableProduct product, long version) {
        ensureIndex();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", product.id());
        doc.put("name", product.name());
        doc.put("description", product.description());
        doc.put("price", product.price());
        doc.put("onSale", product.onSale());
        doc.put("updatedAt", product.updatedAt().toString());
        Response response = call(HttpMethod.PUT,
                "/" + alias + "/_doc/" + product.id() + "?version=" + version + "&version_type=external", doc);
        if (response.status == 200 || response.status == 201) {
            return true;
        }
        if (response.status == 409) {
            return false;
        }
        throw unexpected("색인", response, product.id());
    }

    /**
     * 문서를 지우지 않고 삭제 표시 문서로 바꾼다(검색에서 제외). ES 가 지운 문서의 버전은 {@code index.gc_deletes} 동안만
     * 기억하므로, 진짜로 지우면 그 뒤에 재처리된 옛 행(DLT 재처리 등)이 새 문서로 들어와 지운 상품이 되살아난다.
     * 표시 문서는 버전을 계속 들고 있어 옛 행을 409 로 막는다. 버전 없는 tombstone 은 가장 큰 버전으로 표시해 영구히 막는다
     * (상품 id 는 다시 쓰이지 않는다)
     */
    @Override
    public boolean delete(long productId, Long version) {
        ensureIndex();
        long markerVersion = version == null ? DELETED_FOREVER_VERSION : version;
        Response response = call(HttpMethod.PUT,
                "/" + alias + "/_doc/" + productId + "?version=" + markerVersion + "&version_type=external",
                Map.of("id", productId, "deleted", true));
        if (response.status == 200 || response.status == 201) {
            return true;
        }
        if (response.status == 409) {
            return false;
        }
        throw unexpected("삭제", response, productId);
    }

    @Override
    public SearchPage search(SearchCriteria criteria) {
        try {
            ensureIndex();
            Response response = call(HttpMethod.POST, "/" + alias + "/_search", searchBody(criteria));
            if (response.status != 200) {
                throw new SearchStoreUnavailableException("search returned " + response.status, null);
            }
            JsonNode hits = json.readTree(response.body).path("hits");
            List<SearchableProduct> items = new ArrayList<>();
            for (JsonNode hit : hits.path("hits")) {
                JsonNode s = hit.path("_source");
                items.add(new SearchableProduct(s.path("id").longValue(), s.path("name").asString(),
                        s.path("description").asString(), s.path("price").longValue(), s.path("onSale").booleanValue(),
                        Instant.parse(s.path("updatedAt").asString())));
            }
            return new SearchPage(items, hits.path("total").path("value").longValue());
        } catch (RestClientException | IllegalStateException | JacksonException e) {
            throw new SearchStoreUnavailableException("검색 저장소 응답 없음", e);
        }
    }

    private Map<String, Object> searchBody(SearchCriteria c) {
        List<Object> must = new ArrayList<>();
        if (c.keyword() != null) {
            must.add(Map.of("multi_match", Map.of(
                    "query", c.keyword(), "fields", List.of("name^3", "description"), "operator", "and")));
        }
        List<Object> filter = new ArrayList<>();
        // 삭제 표시 문서(tombstone)는 늘 뺀다
        filter.add(Map.of("bool", Map.of("must_not", List.of(Map.of("term", Map.of("deleted", true))))));
        if (c.onSaleOnly()) {
            filter.add(Map.of("term", Map.of("onSale", true)));
        }
        if (c.minPrice() != null || c.maxPrice() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (c.minPrice() != null) {
                range.put("gte", c.minPrice());
            }
            if (c.maxPrice() != null) {
                range.put("lte", c.maxPrice());
            }
            filter.add(Map.of("range", Map.of("price", range)));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", must.isEmpty() ? List.of(Map.of("match_all", Map.of())) : must);
        bool.put("filter", filter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of("bool", bool));
        body.put("sort", switch (c.sort()) {
            case RELEVANCE -> List.of(Map.of("_score", "desc"), Map.of("id", "asc"));
            case PRICE_ASC -> List.of(Map.of("price", "asc"), Map.of("id", "asc"));
            case PRICE_DESC -> List.of(Map.of("price", "desc"), Map.of("id", "asc"));
            case NEWEST -> List.of(Map.of("updatedAt", "desc"), Map.of("id", "asc"));
        });
        body.put("from", c.from());
        body.put("size", c.size());
        body.put("track_total_hits", true);
        return body;
    }

    /** 별칭이 없으면 {@code <별칭>-v1} 을 만들고 별칭을 건다. 이미 있으면(다른 인스턴스가 만듦) 그대로 쓴다 */
    private void ensureIndex() {
        if (indexReady) {
            return;
        }
        synchronized (this) {
            if (indexReady) {
                return;
            }
            Response alias = call(HttpMethod.HEAD, "/_alias/" + this.alias, null);
            if (alias.status == 404) {
                Map<String, Object> definition = new LinkedHashMap<>(json.readValue(INDEX_DEFINITION, Map.class));
                definition.put("aliases", Map.of(this.alias, Map.of()));
                Response created = call(HttpMethod.PUT, "/" + this.alias + "-v1", definition);
                if (created.status != 200 && !created.body.contains("resource_already_exists_exception")) {
                    throw new IllegalStateException("검색 색인을 만들지 못함: " + created.status + " " + created.body);
                }
                log.info("검색 색인 {}-v1 과 별칭 {} 를 만듦", this.alias, this.alias);
            } else if (alias.status != 200) {
                throw new IllegalStateException("검색 색인 별칭 확인 실패: " + alias.status);
            }
            indexReady = true;
        }
    }

    private Response call(HttpMethod method, String uri, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // 본문을 읽다 끊기면(IOException) 그대로 던진다 → RestClient 가 통신 오류(ResourceAccessException)로 바꾼다
        return spec.exchange((request, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private RuntimeException unexpected(String what, Response response, long productId) {
        if (response.status == 400) {
            return new ProductDocumentRejectedException(
                    "검색 저장소가 " + what + "을 거절함 (productId=" + productId + "): " + response.body);
        }
        // 5xx 등: 일시 장애로 보고 리스너가 재시도한다
        return new IllegalStateException("검색 저장소 " + what + " 실패 (productId=" + productId + "): " + response.status);
    }

    private record Response(int status, String body) {
    }
}
