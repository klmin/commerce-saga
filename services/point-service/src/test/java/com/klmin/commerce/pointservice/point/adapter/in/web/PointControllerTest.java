package com.klmin.commerce.pointservice.point.adapter.in.web;

import com.klmin.commerce.pointservice.point.application.PointAccountNotFoundException;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryQuery;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import com.klmin.commerce.pointservice.point.domain.Points;
import com.klmin.commerce.pointservice.point.domain.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(com.klmin.commerce.pointservice.common.config.SecurityConfig.class)
@WebMvcTest(PointController.class)
class PointControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GetPointBalanceUseCase getPointBalanceUseCase;

    @MockitoBean
    ListMyPointHistoryUseCase listMyPointHistoryUseCase;

    @Test
    void getMyBalance_usesHeaderUser() throws Exception {
        given(getPointBalanceUseCase.getBalance(3L)).willReturn(PointAccount.restore(UserId.of(3L), Points.of(700L), Instant.now()));

        mvc.perform(get("/points/me").with(jwt().jwt(j -> j.subject("3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(3))
                .andExpect(jsonPath("$.balance").value(700));
    }

    @Test
    void getMyBalance_withoutToken_returns401() throws Exception {
        mvc.perform(get("/points/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void getMyBalance_noAccount_returns404() throws Exception {
        given(getPointBalanceUseCase.getBalance(9L)).willThrow(new PointAccountNotFoundException(9L));

        mvc.perform(get("/points/me").with(jwt().jwt(j -> j.subject("9")))).andExpect(status().isNotFound());
    }

    @Test
    void getMyHistory_usesTokenUser_andCursor() throws Exception {
        UUID before = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-24T01:00:00Z");
        given(listMyPointHistoryUseCase.listMyHistory(new ListMyPointHistoryQuery(5L, before, 2))).willReturn(List.of(
                new PointHistoryEntryResult(orderId, "ORDER", orderId, 300L, "REFUNDED", "order cancelled", at, at),
                new PointHistoryEntryResult(UUID.randomUUID(), "SIGNUP_BONUS", null, 1_000L, "GRANTED", null, at,
                        at)));

        mvc.perform(get("/points/me/history").param("before", before.toString()).param("size", "2")
                        .with(jwt().jwt(j -> j.subject("5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entryId").value(orderId.toString()))
                .andExpect(jsonPath("$[0].kind").value("ORDER"))
                .andExpect(jsonPath("$[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$[1].kind").value("SIGNUP_BONUS"))
                .andExpect(jsonPath("$[1].orderId").doesNotExist())
                .andExpect(jsonPath("$[1].amount").value(1000));
    }

    @Test
    void getMyHistory_defaultSize20() throws Exception {
        given(listMyPointHistoryUseCase.listMyHistory(new ListMyPointHistoryQuery(5L, null, 20))).willReturn(List.of());

        mvc.perform(get("/points/me/history").with(jwt().jwt(j -> j.subject("5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getMyHistory_badSizeOrCursor_returns400_withoutToken_401() throws Exception {
        mvc.perform(get("/points/me/history").param("size", "51").with(jwt().jwt(j -> j.subject("5"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/points/me/history").param("size", "0").with(jwt().jwt(j -> j.subject("5"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/points/me/history").param("before", "not-a-uuid").with(jwt().jwt(j -> j.subject("5"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/points/me/history")).andExpect(status().isUnauthorized());
        then(listMyPointHistoryUseCase).should(never()).listMyHistory(any());
    }
}
