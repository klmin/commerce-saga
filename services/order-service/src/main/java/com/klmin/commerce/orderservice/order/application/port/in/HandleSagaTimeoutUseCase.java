package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * 멈춘 Saga 정리 (ADR 0007). 응답이 오지 않는 단계(참여 서비스 장애, 커맨드·응답이 DLT 로 감)에서
 * 같은 커맨드를 다시 보내고, 정방향 단계가 끝내 응답하지 않으면 역순 보상한다.
 */
public interface HandleSagaTimeoutUseCase {

    /** 단계 타임아웃이 지났을 수 있는 진행 중 Saga 의 주문 id (오래 멈춘 순, 한 번에 batch-size 개) */
    List<UUID> findStuckSagas();

    /**
     * Saga 를 잠그고 다시 판단해 재전송하거나 보상을 시작한다 (Saga 변경 + 커맨드 outbox = 한 트랜잭션).
     * 그사이 응답이 와서 진행됐거나 다른 인스턴스가 먼저 처리했으면 아무것도 하지 않고 false
     */
    boolean handleTimeout(UUID orderId);
}
