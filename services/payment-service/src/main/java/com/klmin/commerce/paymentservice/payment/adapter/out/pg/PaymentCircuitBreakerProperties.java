package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * PG 승인 서킷 브레이커 (PROGRESS 4-4절 ③).
 *
 * @param failureRateThreshold       최근 호출 중 실패(타임아웃·오류) 비율이 이 % 이상이면 연다
 * @param slidingWindowSize          비율을 보는 최근 호출 수
 * @param minimumNumberOfCalls       이만큼 호출이 쌓이기 전에는 열지 않는다 (장애 주입 시나리오 몇 건으로 열리지 않게)
 * @param waitDurationInOpenState    열린 뒤 이만큼 지나면 반열림(시험 호출)
 * @param permittedCallsInHalfOpen   반열림에서 시험으로 보내는 호출 수
 */
@ConfigurationProperties("payment.circuit-breaker")
record PaymentCircuitBreakerProperties(boolean enabled, float failureRateThreshold, int slidingWindowSize,
                                       int minimumNumberOfCalls, Duration waitDurationInOpenState,
                                       int permittedCallsInHalfOpen) {
}
