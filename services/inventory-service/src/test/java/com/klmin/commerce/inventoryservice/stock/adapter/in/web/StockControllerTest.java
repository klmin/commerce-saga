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
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.inventoryservice.common.config.SecurityConfig.class)
@WebMvcTest(StockController.class)
class StockControllerTest {

    private static final UUID P1 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    private static final UUID P9 = UUID.fromString("01900000-0000-7000-8000-0000000000a9");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetStockUseCase getStockUseCase;

    @Test
    void getStock() throws Exception {
        given(getStockUseCase.getStock(P1)).willReturn(Stock.restore(ProductId.of(P1), 7, 3, Instant.now()));

        mvc.perform(get("/stocks/" + P1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(P1.toString()))
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.reserved").value(3));
    }

    @Test
    void getStock_notFound_returns404() throws Exception {
        given(getStockUseCase.getStock(P9)).willThrow(new StockNotFoundException(P9));

        mvc.perform(get("/stocks/" + P9)).andExpect(status().isNotFound());
    }

    @Test
    void getStock_invalidId_returns400() throws Exception {
        mvc.perform(get("/stocks/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
