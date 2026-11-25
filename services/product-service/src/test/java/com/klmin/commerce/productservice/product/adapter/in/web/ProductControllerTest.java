package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.common.config.SecurityConfig;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.ManageProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SecurityConfig.class)
@WebMvcTest({ProductController.class, AdminProductController.class})
class ProductControllerTest {

    private static final ProductResult KEYBOARD =
            new ProductResult(1, "무선 키보드", "", 39_000, true, Instant.parse("2026-11-25T00:00:00Z"));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetProductUseCase getProductUseCase;

    @MockitoBean
    ManageProductUseCase manageProductUseCase;

    @Test
    void list_isPublic() throws Exception {
        given(getProductUseCase.list(new ListProductsQuery(0, 20, false)))
                .willReturn(new ProductPageResult(List.of(KEYBOARD), false));

        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].price").value(39_000))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void findByIds_isPublic() throws Exception {
        given(getProductUseCase.findByIds(List.of(1L, 2L))).willReturn(List.of(KEYBOARD));

        mvc.perform(get("/products").param("ids", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].onSale").value(true));
    }

    @Test
    void findByIds_invalid_400() throws Exception {
        mvc.perform(get("/products").param("ids", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("ids", "a")).andExpect(status().isBadRequest());
    }

    @Test
    void get_notFound_404() throws Exception {
        given(getProductUseCase.get(9)).willThrow(new ProductNotFoundException(9));

        mvc.perform(get("/products/9")).andExpect(status().isNotFound());
    }

    @Test
    void admin_requiresAdminRole() throws Exception {
        mvc.perform(get("/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/products").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(manageProductUseCase);
    }

    @Test
    void changePrice_recordsAdminFromToken() throws Exception {
        given(manageProductUseCase.changePrice(any())).willReturn(KEYBOARD);

        mvc.perform(put("/admin/products/1/price")
                        .with(jwt().jwt(j -> j.subject("1000")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":35000}"))
                .andExpect(status().isOk());

        verify(manageProductUseCase).changePrice(new ChangePriceCommand(1, 35_000, 1000));
    }

    @Test
    void changePrice_invalidBody_400() throws Exception {
        mvc.perform(put("/admin/products/1/price")
                        .with(jwt().jwt(j -> j.subject("1000")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(manageProductUseCase);
    }
}
