package com.klmin.commerce.orderservice.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로그를 OTLP 로 Alloy → Loki 에 보낸다 (ADR 0013).
 * <p>
 * Spring Boot 는 OTLP 로그 exporter 까지만 만들고 Logback 에 붙이는 appender 는 만들지 않는다. 그래서 기동이 끝나면
 * root logger 에 OpenTelemetry appender 를 붙인다. span 안에서 찍은 로그는 trace_id·span_id 가 함께 가서
 * Grafana 에서 trace ↔ 로그를 오갈 수 있다. 콘솔 로그는 그대로 둔다. 보내기 여부는 {@code management.logging.export.enabled}
 */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryLoggingConfig {

    @Bean
    OtlpLogAppenderInstaller otlpLogAppenderInstaller(OpenTelemetry openTelemetry) {
        return new OtlpLogAppenderInstaller(openTelemetry);
    }

    static final class OtlpLogAppenderInstaller implements SmartInitializingSingleton, DisposableBean {

        private static final String NAME = "OTLP";

        private final OpenTelemetry openTelemetry;

        OtlpLogAppenderInstaller(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterSingletonsInstantiated() {
            var context = (LoggerContext) LoggerFactory.getILoggerFactory();
            var appender = new OpenTelemetryAppender();
            appender.setName(NAME);
            appender.setContext(context);
            appender.setOpenTelemetry(openTelemetry);
            appender.start();
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            root.detachAppender(NAME); // 같은 JVM 에서 컨텍스트가 다시 뜨면(테스트) 옛 것을 바꾼다
            root.addAppender(appender);
        }

        @Override
        public void destroy() {
            var context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(NAME);
        }
    }
}
