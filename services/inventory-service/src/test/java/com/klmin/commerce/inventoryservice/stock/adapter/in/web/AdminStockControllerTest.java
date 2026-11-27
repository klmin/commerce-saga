package com.klmin.commerce.inventoryservice.stock.adapter.in.web;

import com.klmin.commerce.inventoryservice.common.config.SecurityConfig;
import com.klmin.commerce.inventoryservice.common.error.ConflictException;
import com.klmin.commerce.inventoryservice.stock.application.IdempotencyKeyReusedException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockResult;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.StockAdjustmentPageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SecurityConfig.class)
@WebMvcTest(AdminStockController.class)
class AdminStockControllerTest {

    private static final UUID P1 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");
    private static final UUID USER = UUID.fromString("01900000-0000-7000-8001-000000000001");
    private static final Instant AT = Instant.parse("2026-11-27T00:00:00Z");
    private static final String URL = "/admin/stocks/" + P1 + "/adjustments";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AdjustStockUseCase adjustStockUseCase;

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(ADMIN.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static AdjustStockResult result(boolean replayed) {
        return new AdjustStockResult(P1, 12, 3,
                new AdjustStockResult.Adjustment(7, 10, 12, "입고", ADMIN, AT), replayed);
    }

    @Test
    void adjust_admin_passesSubjectAndKey() throws Exception {
        given(adjustStockUseCase.adjust(new AdjustStockCommand(P1, 10, "입고", ADMIN, "key-1")))
                .willReturn(result(false));

        mvc.perform(post(URL).with(admin()).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":10,\"reason\":\"입고\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.productId").value(P1.toString()))
                .andExpect(jsonPath("$.available").value(12))
                .andExpect(jsonPath("$.reserved").value(3))
                .andExpect(jsonPath("$.adjustment.delta").value(10))
                .andExpect(jsonPath("$.adjustment.availableAfter").value(12))
                .andExpect(jsonPath("$.adjustment.adjustedBy").value(ADMIN.toString()));
    }

    @Test
    void adjust_replay_setsHeader() throws Exception {
        given(adjustStockUseCase.adjust(any())).willReturn(result(true));

        mvc.perform(post(URL).with(admin()).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":10,\"reason\":\"입고\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void adjust_notAdmin_isForbidden() throws Exception {
        mvc.perform(post(URL).with(jwt().jwt(j -> j.subject(USER.toString()))).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1}"))
                .andExpect(status().isUnauthorized());
        then(adjustStockUseCase).should(never()).adjust(any());
    }

    @Test
    void adjust_badInput_is400() throws Exception {
        // 키 없음
        mvc.perform(post(URL).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1}"))
                .andExpect(status().isBadRequest());
        // 수량 없음
        mvc.perform(post(URL).with(admin()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // 0 (command 검증)
        mvc.perform(post(URL).with(admin()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":0}"))
                .andExpect(status().isBadRequest());
        then(adjustStockUseCase).should(never()).adjust(any());
    }

    @Test
    void adjust_conflictAndKeyReuse() throws Exception {
        given(adjustStockUseCase.adjust(any())).willThrow(new ConflictException("가용 재고(3개)보다 많이 차감할 수 없습니다."));
        mvc.perform(post(URL).with(admin()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("가용 재고(3개)보다 많이 차감할 수 없습니다."));

        // 위 스텁이 예외를 던지므로 given(mock.adjust(..)) 로 다시 스텁하면 호출 자체가 터진다
        willThrow(new IdempotencyKeyReusedException()).given(adjustStockUseCase).adjust(any());
        mvc.perform(post(URL).with(admin()).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-5}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void adjustments_list() throws Exception {
        given(adjustStockUseCase.adjustments(P1, 0, 20)).willReturn(new StockAdjustmentPageResult(
                List.of(new AdjustStockResult.Adjustment(7, -2, 8, null, ADMIN, AT)), true));

        mvc.perform(get(URL).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].delta").value(-2))
                .andExpect(jsonPath("$.items[0].availableAfter").value(8))
                .andExpect(jsonPath("$.hasNext").value(true));

        mvc.perform(get(URL).with(jwt().jwt(j -> j.subject(USER.toString())))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "?size=101").with(admin())).andExpect(status().isBadRequest());
    }
}
