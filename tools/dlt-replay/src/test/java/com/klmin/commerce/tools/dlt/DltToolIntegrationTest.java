package com.klmin.commerce.tools.dlt;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DltToolIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"));

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private DltClient client;
    private DltTool tool;
    private String topic;
    private String dlt;

    @BeforeEach
    void setUp() {
        client = new DltClient(KAFKA.getBootstrapServers());
        tool = new DltTool(client, new PrintStream(output, true, StandardCharsets.UTF_8));
        topic = "t" + UUID.randomUUID().toString().substring(0, 8) + ".command";
        dlt = topic + ".DLT";
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private void putIntoDlt(String key, String id, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(dlt, 0, key, value,
                    DltRecordTest.springDltHeaders(topic, id, "ReserveStock"))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ConsumerRecord<String, String>> readOriginal(int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            // 기대 건수를 받은 뒤에도 3초까지는 더 읽어 추가 메시지가 없는지 본다
            Instant start = Instant.now();
            while (true) {
                consumer.poll(Duration.ofMillis(300)).forEach(records::add);
                Duration elapsed = Duration.between(start, Instant.now());
                if (records.size() > expected
                        || records.size() == expected && elapsed.toSeconds() >= 3
                        || elapsed.toSeconds() >= 15) {
                    break;
                }
            }
        }
        return records;
    }

    @Test
    void list_showsMessagesWithCause() {
        putIntoDlt("order-1", "11111111-1111-1111-1111-111111111111", "{\"lines\":[]}");

        assertThat(tool.run(new String[]{"list", dlt})).isEqualTo(DltTool.OK);

        assertThat(out()).contains("[0:0] key=order-1 type=ReserveStock id=11111111-1111-1111-1111-111111111111")
                .contains("from=" + topic + "/2@42")
                .contains("error: com.example.NonRetryableMessageException: orderId missing")
                .contains("value: {\"lines\":[]}")
                .contains("1 message(s) in " + dlt);
    }

    @Test
    void replayOffset_sendsOnlyThatMessage_toOriginalTopic_withSameId() {
        putIntoDlt("order-1", "11111111-1111-1111-1111-111111111111", "{\"a\":1}");
        putIntoDlt("order-2", "22222222-2222-2222-2222-222222222222", "{\"b\":2}");

        assertThat(tool.run(new String[]{"replay", dlt, "--offset", "0:1"})).isEqualTo(DltTool.OK);

        List<ConsumerRecord<String, String>> replayed = readOriginal(1);
        assertThat(replayed).hasSize(1);
        ConsumerRecord<String, String> r = replayed.getFirst();
        assertThat(r.key()).isEqualTo("order-2");
        assertThat(r.value()).isEqualTo("{\"b\":2}");
        assertThat(new String(r.headers().lastHeader("id").value(), StandardCharsets.UTF_8))
                .isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(r.headers().lastHeader(DltRecord.EXCEPTION_MESSAGE)).isNull();
        assertThat(out()).contains("1 message(s) replayed");
    }

    @Test
    void replayAllDryRun_sendsNothing() {
        putIntoDlt("order-1", UUID.randomUUID().toString(), "{}");

        assertThat(tool.run(new String[]{"replay", dlt, "--all", "--dry-run"})).isEqualTo(DltTool.OK);

        assertThat(out()).contains("would replay -> " + topic).contains("1 message(s) selected");
        assertThat(readOriginal(0)).isEmpty();
    }

    @Test
    void replayUnknownOffset_failsWithoutSending() {
        putIntoDlt("order-1", UUID.randomUUID().toString(), "{}");

        assertThat(tool.run(new String[]{"replay", dlt, "--offset", "0:0,0:9"})).isEqualTo(DltTool.FAILED);

        assertThat(out()).contains("not found").contains("0:9");
        assertThat(readOriginal(0)).isEmpty();
    }

    @Test
    void badUsage() {
        assertThat(tool.run(new String[]{"list"})).isEqualTo(DltTool.USAGE);
        assertThat(tool.run(new String[]{"list", "inventory.command"})).isEqualTo(DltTool.USAGE);
        assertThat(tool.run(new String[]{"replay", dlt})).isEqualTo(DltTool.USAGE);
        assertThat(tool.run(new String[]{"replay", dlt, "--all", "--offset", "0:0"})).isEqualTo(DltTool.USAGE);
    }
}
