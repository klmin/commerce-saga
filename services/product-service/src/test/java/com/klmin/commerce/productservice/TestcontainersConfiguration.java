package com.klmin.commerce.productservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String RUSTFS_ACCESS_KEY = "rustfs-test";
    static final String RUSTFS_SECRET_KEY = "rustfs-test-secret";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }

    // 상품 이미지 저장소 (ADR 0021). compose 와 같은 이미지, 테스트용 계정
    @Bean
    GenericContainer<?> rustfsContainer() {
        return new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:1.0.0"))
                .withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", RUSTFS_SECRET_KEY)
                .withExposedPorts(9000)
                .waitingFor(Wait.forListeningPort());
    }

    @Bean
    DynamicPropertyRegistrar rustfsProperties(GenericContainer<?> rustfsContainer) {
        return registry -> {
            registry.add("product.images.endpoint",
                    () -> "http://" + rustfsContainer.getHost() + ":" + rustfsContainer.getMappedPort(9000));
            registry.add("product.images.access-key", () -> RUSTFS_ACCESS_KEY);
            registry.add("product.images.secret-key", () -> RUSTFS_SECRET_KEY);
        };
    }
}
