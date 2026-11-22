package com.klmin.commerce.tools.dlt;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DLT 읽기·원래 토픽으로 다시 보내기.
 * <p>
 * 컨슈머 그룹을 쓰지 않는다(파티션을 직접 assign). 그래서 서비스의 오프셋에 영향이 없고, 읽을 때마다 DLT 처음부터
 * "지금 끝"까지 전부 본다. Kafka 는 메시지를 골라 지울 수 없어 재처리한 메시지도 DLT 에 남는다.
 */
final class DltClient implements AutoCloseable {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private KafkaProducer<String, String> producer;

    DltClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** DLT 의 모든 메시지 (파티션·오프셋 순) */
    List<DltRecord> readAll(String dltTopic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> infos = consumer.partitionsFor(dltTopic, READ_TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                throw new IllegalArgumentException("topic not found: " + dltTopic);
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(dltTopic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, READ_TIMEOUT);

            List<DltRecord> records = new ArrayList<>();
            Instant deadline = Instant.now().plus(READ_TIMEOUT);
            while (!reachedEnd(consumer, end)) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("timed out reading " + dltTopic);
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    // 읽기 시작한 뒤 새로 들어온 메시지는 이번 대상이 아니다
                    if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) {
                        records.add(DltRecord.from(record));
                    }
                }
            }
            records.sort(Comparator.comparingInt(DltRecord::partition).thenComparingLong(DltRecord::offset));
            return records;
        }
    }

    /** 원래 토픽으로 보내고 브로커 ack 를 기다린다 */
    RecordMetadata replay(DltRecord record) {
        try {
            return producer().send(record.toReplay()).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("replay failed: " + record.dltTopic() + " " + record.position(), e);
        }
    }

    private static boolean reachedEnd(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }

    private KafkaProducer<String, String> producer() {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producer = new KafkaProducer<>(props);
        }
        return producer;
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(10));
        }
    }
}
