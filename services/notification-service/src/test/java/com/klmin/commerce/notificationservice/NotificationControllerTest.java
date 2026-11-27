package com.klmin.commerce.notificationservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SecurityConfig.class)
@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    private static final UUID USER = UUID.fromString("01900000-0000-7000-8001-000000000001");
    private static final UUID CURSOR = UUID.fromString("01900000-0000-7000-8002-000000000010");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NotificationRepository repository;

    @Test
    void myNotifications_userFromToken_cursorIsUuid() throws Exception {
        given(repository.findByUser(USER, CURSOR, 20)).willReturn(List.of(new NotificationRepository.Notification(
                UUID.fromString("01900000-0000-7000-8002-000000000009"), UUID.randomUUID(), "OrderCompleted", "완료",
                Instant.parse("2026-11-25T00:00:00Z"))));

        mvc.perform(get("/notifications/me").param("before", CURSOR.toString())
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("01900000-0000-7000-8002-000000000009"));

        verify(repository).findByUser(USER, CURSOR, 20);
    }

    @Test
    void myNotifications_invalidInput_400() throws Exception {
        for (String before : List.of("1", "abc")) {
            mvc.perform(get("/notifications/me").param("before", before)
                            .with(jwt().jwt(j -> j.subject(USER.toString()))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/notifications/me").param("size", "500")
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
