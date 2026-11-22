package com.klmin.commerce.tools.dlt;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DltRecordTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Spring Kafka DeadLetterPublishingRecoverer 가 만드는 모양 */
    static RecordHeaders springDltHeaders(String originalTopic, String id, String type) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("id", utf8(id));
        headers.add("type", utf8(type));
        headers.add(DltRecord.ORIGINAL_TOPIC, utf8(originalTopic));
        headers.add(DltRecord.ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(2).array());
        headers.add(DltRecord.ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(42L).array());
        headers.add(DltRecord.EXCEPTION_FQCN, utf8("org.springframework.kafka.listener.ListenerExecutionFailedException"));
        headers.add(DltRecord.EXCEPTION_CAUSE_FQCN, utf8("com.example.NonRetryableMessageException"));
        headers.add(DltRecord.EXCEPTION_MESSAGE, utf8("orderId missing"));
        return headers;
    }

    @Test
    void toReplay_sendsToOriginalTopic_keepingKeyValueAndMessageHeaders_withoutDltHeaders() {
        DltRecord record = new DltRecord("inventory.command.DLT", 1, 7L, "order-1", "{\"x\":1}",
                springDltHeaders("inventory.command", "11111111-1111-1111-1111-111111111111", "ReserveStock"));

        ProducerRecord<String, String> replay = record.toReplay();

        assertThat(replay.topic()).isEqualTo("inventory.command");
        assertThat(replay.partition()).isNull();   // 키(orderId)로 파티션이 정해지게
        assertThat(replay.key()).isEqualTo("order-1");
        assertThat(replay.value()).isEqualTo("{\"x\":1}");
        List<String> keys = new ArrayList<>();
        for (Header h : replay.headers()) {
            keys.add(h.key());
        }
        assertThat(keys).containsExactly("id", "type", DltRecord.REPLAYED_FROM);
        assertThat(new String(replay.headers().lastHeader("id").value(), StandardCharsets.UTF_8))
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(new String(replay.headers().lastHeader(DltRecord.REPLAYED_FROM).value(), StandardCharsets.UTF_8))
                .isEqualTo("inventory.command.DLT:1:7");
    }

    @Test
    void describesOriginAndError() {
        DltRecord record = new DltRecord("point.command.DLT", 0, 3L, "k", "{}",
                springDltHeaders("point.command", "id-1", "UsePoint"));

        assertThat(record.origin()).isEqualTo("point.command/2@42");
        assertThat(record.error()).isEqualTo("com.example.NonRetryableMessageException: orderId missing");
        assertThat(record.type()).isEqualTo("UsePoint");
        assertThat(record.messageId()).isEqualTo("id-1");
    }

    @Test
    void withoutOriginalTopicHeader_stripsDltSuffix() {
        DltRecord record = new DltRecord("payment.command.DLT", 0, 0L, "k", "{}", new RecordHeaders());

        assertThat(record.toReplay().topic()).isEqualTo("payment.command");
    }

    @Test
    void replayingAReplayedMessage_keepsSingleReplayedFromHeader() {
        RecordHeaders headers = springDltHeaders("order.events", "id-1", "OrderCompleted");
        headers.add(DltRecord.REPLAYED_FROM, utf8("order.events.DLT:0:1"));
        DltRecord record = new DltRecord("order.events.DLT", 0, 5L, "k", "{}", headers);

        Iterable<Header> replayed = record.toReplay().headers().headers(DltRecord.REPLAYED_FROM);

        assertThat(replayed).hasSize(1);
    }

    @Test
    void refusesToReplayIntoADlt() {
        DltRecord record = new DltRecord("x.command.DLT", 0, 0L, "k", "{}",
                springDltHeaders("x.command.DLT", "id", "T"));

        assertThatThrownBy(record::toReplay).isInstanceOf(IllegalStateException.class);
    }
}
