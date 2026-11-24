package com.klmin.commerce.inventoryservice.stock.adapter.in.web;

import com.klmin.commerce.inventoryservice.stock.application.StockNotFoundException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.inventoryservice.common.config.SecurityConfig.class)
@WebMvcTest(StockController.class)
class StockControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetStockUseCase getStockUseCase;

    @Test
    void getStock() throws Exception {
        given(getStockUseCase.getStock(1L)).willReturn(Stock.restore(ProductId.of(1L), 7, 3, Instant.now()));

        mvc.perform(get("/stocks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.reserved").value(3));
    }

    @Test
    void getStock_notFound_returns404() throws Exception {
        given(getStockUseCase.getStock(9L)).willThrow(new StockNotFoundException(9L));

        mvc.perform(get("/stocks/9")).andExpect(status().isNotFound());
    }

    @Test
    void getStock_invalidId_returns400() throws Exception {
        mvc.perform(get("/stocks/0")).andExpect(status().isBadRequest());
    }
}
