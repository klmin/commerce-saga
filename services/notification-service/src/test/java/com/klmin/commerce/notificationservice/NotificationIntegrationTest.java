package com.klmin.commerce.notificationservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.order.OrderCancelled;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class NotificationIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    NotificationRepository repository;

    @Autowired
    JsonMapper json;

    @Autowired
    MockMvc mvc;

    @Test
    void orderEvents_createOneNotificationEach_evenIfDelivered_twice() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();

        send(completed, OrderCompleted.TYPE, new OrderCompleted(completed, userId, 2000L, 500L, 1500L));
        send(completed, OrderCompleted.TYPE, new OrderCompleted(completed, userId, 2000L, 500L, 1500L));
        send(cancelled, OrderCancelled.TYPE, new OrderCancelled(cancelled, userId, "declined"));
        send(cancelled, "SomethingNew", "{}"); // 관심 없는 타입은 건너뜀

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findByUser(userId, null, 10)).hasSize(2));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findByUser(userId, null, 10)).hasSize(2));

        mvc.perform(get("/notifications/me").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value(OrderCancelled.TYPE))
                .andExpect(jsonPath("$[1].type").value(OrderCompleted.TYPE));

        // 커서: id 가 UUIDv7 이라 id 순 = 최신순. 첫 장의 마지막 id 를 before 로 넘기면 그보다 오래된 것
        String newest = JsonPath.read(mvc.perform(get("/notifications/me").param("size", "1")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value(OrderCancelled.TYPE))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        assertThat(UUID.fromString(newest).version()).as("UUIDv7").isEqualTo(7);
        mvc.perform(get("/notifications/me").param("before", newest)
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value(OrderCompleted.TYPE));
        mvc.perform(get("/notifications/me").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void myNotifications_validatesInput() throws Exception {
        mvc.perform(get("/notifications/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications/me").with(jwt().jwt(j -> j.subject("01900000-0000-7000-8001-000000000001"))).param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    private void send(UUID orderId, String type, Object payload) {
        String value = payload instanceof String s ? s : json.writeValueAsString(payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.ORDER_EVENTS, orderId.toString(), value);
        record.headers().add(MessageHeaders.ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }
}
