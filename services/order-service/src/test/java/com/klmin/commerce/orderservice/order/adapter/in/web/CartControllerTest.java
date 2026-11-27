package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.port.in.CartResult;
import com.klmin.commerce.orderservice.order.application.port.in.CartUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.orderservice.common.config.SecurityConfig.class)
@WebMvcTest(CartController.class)
class CartControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CartUseCase cartUseCase;

    @Test
    void get_usesTokenSubject_andShowsPriceChange() throws Exception {
        given(cartUseCase.get(uid(7))).willReturn(new CartResult(
                List.of(new CartResult.Item(pid(1), "키보드", 2, 39_000L, 42_000L, true)), true));

        mvc.perform(get("/cart").with(jwt().jwt(j -> j.subject(uid(7).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(pid(1).toString()))
                .andExpect(jsonPath("$.items[0].priceChanged").value(true))
                .andExpect(jsonPath("$.items[0].currentPrice").value(42_000))
                .andExpect(jsonPath("$.pricesAvailable").value(true));
    }

    @Test
    void withoutToken_401() throws Exception {
        mvc.perform(get("/cart")).andExpect(status().isUnauthorized());
        verifyNoInteractions(cartUseCase);
    }

    @Test
    void put_invalidQuantity_400() throws Exception {
        mvc.perform(put("/cart/items/" + pid(1)).with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/cart/items/" + pid(1)).with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1001}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cartUseCase);
    }

    @Test
    void put_malformedProductId_400() throws Exception {
        mvc.perform(put("/cart/items/1").with(jwt().jwt(j -> j.subject(uid(7).toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cartUseCase);
    }
}
