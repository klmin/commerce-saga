package com.klmin.commerce.searchservice.product.adapter.out.elasticsearch;

import com.klmin.commerce.searchservice.TestcontainersConfiguration;
import com.klmin.commerce.searchservice.product.application.SearchStoreUnavailableException;
import com.klmin.commerce.searchservice.product.domain.SearchCriteria;
import com.klmin.commerce.searchservice.product.domain.SearchPage;
import com.klmin.commerce.searchservice.product.domain.SearchSort;
import com.klmin.commerce.searchservice.product.domain.SearchableProduct;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.web.client.RestClient;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Elasticsearch 로 색인 버전(외부 버전 = LSN)·한글 부분 검색·필터·정렬 확인 (ADR 0010) */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchProductAdapterTest {

    @Container
    static final ElasticsearchContainer ES = TestcontainersConfiguration.elasticsearch();

    private ElasticsearchProductAdapter adapter;

    private static SearchableProduct product(long id, String name, String description, long price, boolean onSale,
                                             String updatedAt) {
        return new SearchableProduct(id, name, description, price, onSale, Instant.parse(updatedAt));
    }

    private ElasticsearchProductAdapter adapter(String uri, String index) {
        return new ElasticsearchProductAdapter(RestClient.builder(),
                new ElasticsearchProperties(uri, index, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                JsonMapper.builder().build());
    }

    private void refresh(String index) {
        RestClient.create("http://" + ES.getHttpHostAddress()).post().uri("/" + index + "/_refresh").retrieve().toBodilessEntity();
    }

    private SearchPage search(String keyword, Long min, Long max, boolean onSaleOnly, SearchSort sort) {
        return adapter.search(new SearchCriteria(keyword, min, max, onSaleOnly, sort, 0, 20));
    }

    @BeforeAll
    void seed() {
        adapter = adapter("http://" + ES.getHttpHostAddress(), "products");
        adapter.save(product(1, "무선 키보드", "", 39_000, true, "2026-11-25T01:00:00Z"), 10);
        adapter.save(product(2, "무선 마우스", "", 25_000, true, "2026-11-25T02:00:00Z"), 10);
        adapter.save(product(3, "USB-C 허브", "", 18_000, true, "2026-11-25T03:00:00Z"), 10);
        adapter.save(product(4, "한정판 키캡", "재고 1개로 시작", 12_000, true, "2026-11-25T04:00:00Z"), 10);
        adapter.save(product(5, "단종 모니터암", "", 45_000, false, "2026-11-25T05:00:00Z"), 10);
        refresh("products");
    }

    @Test
    void externalVersion_ignoresSameAndOlderChanges() {
        // 다른 시험의 건수에 섞이지 않게 별도 색인(별칭)으로
        ElasticsearchProductAdapter adapter = adapter("http://" + ES.getHttpHostAddress(), "versions");
        assertThat(adapter.save(product(9, "버전 시험", "", 1_000, true, "2026-11-25T00:00:00Z"), 100)).isTrue();
        // 같은 변경 재전달, 늦게 온 옛 변경 → 건너뜀
        assertThat(adapter.save(product(9, "버전 시험", "", 1_000, true, "2026-11-25T00:00:00Z"), 100)).isFalse();
        assertThat(adapter.save(product(9, "버전 시험", "", 500, true, "2026-11-25T00:00:00Z"), 50)).isFalse();
        assertThat(adapter.save(product(9, "버전 시험", "", 2_000, true, "2026-11-25T00:00:01Z"), 101)).isTrue();
        refresh("versions");

        assertThat(adapter.search(new SearchCriteria("버전 시험", null, null, true, null, 0, 20)).items())
                .singleElement().satisfies(p -> assertThat(p.price()).isEqualTo(2_000));

        assertThat(adapter.delete(9, 50L)).isFalse(); // 옛 삭제는 무시
        assertThat(adapter.delete(9, 102L)).isTrue();
        assertThat(adapter.delete(9, 103L)).isFalse(); // 이미 없음
    }

    @Test
    void koreanPartialMatch_onNameAndDescription() {
        assertThat(search("키보", null, null, true, null).items()).extracting(SearchableProduct::id).containsExactly(1L);
        assertThat(search("무선", null, null, true, null).items()).extracting(SearchableProduct::id)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(search("usb", null, null, true, null).items()).extracting(SearchableProduct::id).containsExactly(3L);
        // 설명에서도 찾는다
        assertThat(search("재고", null, null, true, null).items()).extracting(SearchableProduct::id).containsExactly(4L);
        // 모든 글자 조각이 있어야 한다 (키 + 캡 → 키캡만, 키보드는 아님)
        assertThat(search("키캡", null, null, true, null).items()).extracting(SearchableProduct::id).containsExactly(4L);
    }

    @Test
    void onSaleFilter_priceRange_andSorts() {
        assertThat(search(null, null, null, true, null).total()).isEqualTo(4);
        assertThat(search("모니터", null, null, true, null).items()).isEmpty();
        assertThat(search("모니터", null, null, false, null).items()).extracting(SearchableProduct::id).containsExactly(5L);

        assertThat(search(null, 15_000L, 30_000L, true, SearchSort.PRICE_ASC).items())
                .extracting(SearchableProduct::id).containsExactly(3L, 2L);
        assertThat(search(null, null, null, true, SearchSort.PRICE_DESC).items())
                .extracting(SearchableProduct::id).startsWith(1L, 2L);
        assertThat(search(null, null, null, true, SearchSort.NEWEST).items())
                .extracting(SearchableProduct::id).startsWith(4L, 3L);
    }

    @Test
    void storeDown_isUnavailable() {
        ElasticsearchProductAdapter down = adapter("http://localhost:1", "products");

        assertThatThrownBy(() -> down.search(new SearchCriteria(null, null, null, true, null, 0, 20)))
                .isInstanceOf(SearchStoreUnavailableException.class);
    }
}
