package com.klmin.commerce.notificationservice;

import com.klmin.commerce.contract.order.OrderCancelled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OrderCancelled.afterCompletion 은 추가한 필드다(ADR 0020). 필드가 없는 옛 메시지도 읽혀야 한다 (ADR 0005) */
class OrderCancelledCompatibilityTest {

    private static final UUID ORDER = UUID.fromString("01900000-0000-7000-8002-000000000001");
    private static final UUID USER = UUID.fromString("01900000-0000-7000-8001-000000000001");

    private final JsonMapper json = new JsonMapper();

    @Test
    void oldMessageWithoutField_readsAsNotAfterCompletion() {
        OrderCancelled event = json.readValue(
                "{\"orderId\":\"" + ORDER + "\",\"userId\":\"" + USER + "\",\"reason\":\"결제 실패\"}",
                OrderCancelled.class);

        assertThat(event).isEqualTo(new OrderCancelled(ORDER, USER, "결제 실패", false));
    }

    @Test
    void newMessage_roundTrips() {
        OrderCancelled event = new OrderCancelled(ORDER, USER, "사용자 취소", true);

        assertThat(json.readValue(json.writeValueAsString(event), OrderCancelled.class)).isEqualTo(event);
    }
}
