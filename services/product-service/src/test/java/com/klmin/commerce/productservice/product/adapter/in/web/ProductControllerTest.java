package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.common.config.SecurityConfig;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditResult;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    private static final UUID ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ID2 = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");
    private static final UUID USER = UUID.fromString("01900000-0000-7000-8001-000000000001");

    private static final ProductResult KEYBOARD =
            new ProductResult(ID, "무선 키보드", "", 39_000, true, Instant.parse("2026-11-25T00:00:00Z"));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetProductUseCase getProductUseCase;

    @MockitoBean
    ManageProductUseCase manageProductUseCase;

    // 실제 Bearer 토큰 경로(디코드 → converter)를 태우려고 디코더만 바꾼다
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void list_isPublic() throws Exception {
        given(getProductUseCase.list(new ListProductsQuery(0, 20, false)))
                .willReturn(new ProductPageResult(List.of(KEYBOARD), false));

        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.items[0].price").value(39_000))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void findByIds_isPublic() throws Exception {
        given(getProductUseCase.findByIds(List.of(ID, ID2))).willReturn(List.of(KEYBOARD));

        mvc.perform(get("/products").param("ids", ID + "," + ID2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].onSale").value(true));
    }

    @Test
    void findByIds_invalid_400() throws Exception {
        mvc.perform(get("/products").param("ids", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("ids", ID + ",x")).andExpect(status().isBadRequest());
        mvc.perform(get("/products").param("ids", "a")).andExpect(status().isBadRequest());
    }

    @Test
    void get_notFound_404() throws Exception {
        UUID missing = UUID.fromString("01900000-0000-7000-8000-000000000009");
        given(getProductUseCase.get(missing)).willThrow(new ProductNotFoundException(missing));

        mvc.perform(get("/products/" + missing)).andExpect(status().isNotFound());
    }

    @Test
    void get_malformedId_400() throws Exception {
        mvc.perform(get("/products/1")).andExpect(status().isBadRequest());
        verifyNoInteractions(getProductUseCase);
    }

    @Test
    void admin_requiresAdminRole() throws Exception {
        mvc.perform(get("/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/products").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(manageProductUseCase);
    }

    @Test
    void changePrice_recordsAdminFromToken() throws Exception {
        given(manageProductUseCase.changePrice(any())).willReturn(KEYBOARD);

        mvc.perform(put("/admin/products/" + ID + "/price")
                        .with(jwt().jwt(j -> j.subject(ADMIN.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":35000}"))
                .andExpect(status().isOk());

        verify(manageProductUseCase).changePrice(new ChangePriceCommand(ID, 35_000, ADMIN));
    }

    @Test
    void changeSaleStatus_recordsAdminFromToken() throws Exception {
        given(manageProductUseCase.changeSaleStatus(any())).willReturn(KEYBOARD);

        mvc.perform(put("/admin/products/" + ID + "/sale")
                        .with(jwt().jwt(j -> j.subject(ADMIN.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onSale\":false}"))
                .andExpect(status().isOk());

        verify(manageProductUseCase).changeSaleStatus(new ChangeSaleStatusCommand(ID, false, ADMIN));
    }

    @Test
    void auditLog_adminOnly() throws Exception {
        given(getProductUseCase.auditLog(ID, 0, 20)).willReturn(new ProductAuditPageResult(List.of(
                new ProductAuditResult("PRICE_CHANGED",
                        new ProductAuditResult.Snapshot("무선 키보드", "", 39_000L, true),
                        new ProductAuditResult.Snapshot("무선 키보드", "", 35_000L, true),
                        ADMIN, Instant.parse("2026-11-25T00:00:00Z")),
                new ProductAuditResult("REGISTERED", null,
                        new ProductAuditResult.Snapshot("무선 키보드", "", 39_000L, true),
                        ADMIN, Instant.parse("2026-11-24T00:00:00Z"))), false));

        mvc.perform(get("/admin/products/" + ID + "/audit-log").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/products/" + ID + "/audit-log")
                        .with(jwt().jwt(j -> j.subject(ADMIN.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.items[0].before.price").value(39_000))
                .andExpect(jsonPath("$.items[0].after.price").value(35_000))
                .andExpect(jsonPath("$.items[0].changedBy").value(ADMIN.toString()))
                .andExpect(jsonPath("$.items[1].before").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void changePrice_invalidBody_400() throws Exception {
        mvc.perform(put("/admin/products/" + ID + "/price")
                        .with(jwt().jwt(j -> j.subject(ADMIN.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(manageProductUseCase);
    }

    // roles 클레임 → ROLE_* 는 Boot 가 설정(authorities-claim-name·authority-prefix)으로 만든 기본 converter 가 한다
    @Test
    void bearerToken_rolesClaim_becomesRole() throws Exception {
        given(jwtDecoder.decode("user")).willReturn(token(USER.toString(), "USER"));
        given(jwtDecoder.decode("admin")).willReturn(token(ADMIN.toString(), "ADMIN"));
        given(manageProductUseCase.changePrice(any())).willReturn(KEYBOARD);

        mvc.perform(put("/admin/products/" + ID + "/price")
                        .header("Authorization", "Bearer user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":35000}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(manageProductUseCase);

        mvc.perform(put("/admin/products/" + ID + "/price")
                        .header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":35000}"))
                .andExpect(status().isOk());
        verify(manageProductUseCase).changePrice(new ChangePriceCommand(ID, 35_000, ADMIN));
    }

    private static Jwt token(String subject, String role) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).claim("roles", List.of(role))
                .issuedAt(Instant.parse("2026-11-25T00:00:00Z")).expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
    }
}
