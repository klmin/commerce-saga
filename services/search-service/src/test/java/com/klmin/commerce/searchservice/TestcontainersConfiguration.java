package com.klmin.commerce.searchservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** compose·Helm 과 같은 버전. 로컬처럼 보안을 끄고 힙을 줄인다 */
    public static ElasticsearchContainer elasticsearch() {
        return new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.3"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }

    @Bean
    ElasticsearchContainer elasticsearchContainer() {
        return elasticsearch();
    }

    @Bean
    DynamicPropertyRegistrar elasticsearchProperties(ElasticsearchContainer elasticsearch) {
        return registry -> registry.add("search.elasticsearch.uri", () -> "http://" + elasticsearch.getHttpHostAddress());
    }
}
