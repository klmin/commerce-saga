package com.klmin.commerce.pointservice.point.adapter.in.web;

import com.klmin.commerce.pointservice.point.application.PointAccountNotFoundException;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.BDDMockito.given;
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

    @Test
    void getMyBalance_usesHeaderUser() throws Exception {
        given(getPointBalanceUseCase.getBalance(3L)).willReturn(PointAccount.restore(3L, 700L, Instant.now()));

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
}
