package com.klmin.commerce.searchservice.product.adapter.in.web;

import com.klmin.commerce.searchservice.common.config.SecurityConfig;
import com.klmin.commerce.searchservice.common.error.DomainValidationException;
import com.klmin.commerce.searchservice.product.application.SearchStoreUnavailableException;
import com.klmin.commerce.searchservice.product.application.port.in.ProductSearchResult;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsQuery;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SecurityConfig.class)
@WebMvcTest(SearchController.class)
class SearchControllerTest {

    private static final UUID ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SearchProductsUseCase searchProductsUseCase;

    @Test
    void search_isPublic_andPassesParameters() throws Exception {
        given(searchProductsUseCase.search(any())).willReturn(new ProductSearchResult(
                List.of(new ProductSearchResult.Item(ID, "무선 키보드", "", 39_000, true)), 1, 0, 20));

        mvc.perform(get("/search/products").param("q", "키보드").param("maxPrice", "50000").param("sort", "price-asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.items[0].name").value("무선 키보드"))
                .andExpect(jsonPath("$.items[0].onSale").value(true));

        verify(searchProductsUseCase).search(new SearchProductsQuery("키보드", null, 50_000L, true, "price-asc", 0, 20));
    }

    @Test
    void invalidCriteria_is400() throws Exception {
        given(searchProductsUseCase.search(any())).willThrow(new DomainValidationException("size 는 1~100 이어야 합니다"));

        mvc.perform(get("/search/products").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storeDown_is503_withoutInternalDetail() throws Exception {
        given(searchProductsUseCase.search(any()))
                .willThrow(new SearchStoreUnavailableException("connection refused to es:9200", null));

        mvc.perform(get("/search/products"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("검색을 잠시 사용할 수 없습니다."));
    }

    @Test
    void otherPaths_needToken() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
