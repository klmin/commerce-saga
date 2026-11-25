package com.klmin.commerce.searchservice.product.domain;

import com.klmin.commerce.searchservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCriteriaTest {

    @Test
    void blankKeywordMeansAll_andDefaultsToRelevance() {
        SearchCriteria c = new SearchCriteria("  ", null, null, true, null, 0, 20);

        assertThat(c.keyword()).isNull();
        assertThat(c.sort()).isEqualTo(SearchSort.RELEVANCE);
    }

    @Test
    void trimsKeyword_andComputesFrom() {
        SearchCriteria c = new SearchCriteria("  키보드 ", 1_000L, 50_000L, false, SearchSort.PRICE_ASC, 2, 10);

        assertThat(c.keyword()).isEqualTo("키보드");
        assertThat(c.from()).isEqualTo(20);
    }

    @Test
    void rejectsInvalidRangesAndPaging() {
        assertThatThrownBy(() -> new SearchCriteria(null, 10L, 5L, true, null, 0, 20))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SearchCriteria(null, -1L, null, true, null, 0, 20))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, true, null, -1, 20))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, true, null, 0, 101))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SearchCriteria("가".repeat(101), null, null, true, null, 0, 20))
                .isInstanceOf(DomainValidationException.class);
        // 앞에서부터 10,000건까지만
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, true, null, 100, 100))
                .isInstanceOf(DomainValidationException.class);
        assertThat(new SearchCriteria(null, null, null, true, null, 99, 100).from()).isEqualTo(9_900);
    }

    @Test
    void parsesSort() {
        assertThat(SearchSort.parse("price-asc")).isEqualTo(SearchSort.PRICE_ASC);
        assertThat(SearchSort.parse("NEWEST")).isEqualTo(SearchSort.NEWEST);
        assertThat(SearchSort.parse(null)).isEqualTo(SearchSort.RELEVANCE);
        assertThatThrownBy(() -> SearchSort.parse("cheap")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void searchableProductRules() {
        assertThatThrownBy(() -> new SearchableProduct(0, "a", "", 1, true, java.time.Instant.EPOCH))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SearchableProduct(1, " ", "", 1, true, java.time.Instant.EPOCH))
                .isInstanceOf(DomainValidationException.class);
        assertThat(new SearchableProduct(1, "a", null, 1, true, java.time.Instant.EPOCH).description()).isEmpty();
    }
}
