package com.klmin.commerce.inventoryservice.stock.application.port.out;

import com.klmin.commerce.inventoryservice.stock.domain.StockReservation;

import java.util.Optional;
import java.util.UUID;

public interface StockReservationPort {

    Optional<StockReservation> findByOrderId(UUID orderId);

    /**
     * 예약 기록을 비관적 락(SELECT ... FOR UPDATE)으로 잠가 가져온다.
     * 해제(inventory.command)와 확정(order.events)은 다른 리스너에서 동시에 올 수 있어서,
     * 상태 전이를 이 락으로 줄 세운다. 뒤에 온 쪽은 앞쪽 커밋 후의 상태를 읽는다.
     */
    Optional<StockReservation> findByOrderIdForUpdate(UUID orderId);

    /** 같은 orderId 가 이미 있으면 예외 (동시에 들어온 중복 커맨드 중 하나만 커밋된다) */
    void insert(StockReservation reservation);

    /** 상태 변경을 반영한다. 동시에 다른 트랜잭션이 바꿨으면 낙관적 락 예외 (메시지는 재처리된다) */
    void update(StockReservation reservation);
}
