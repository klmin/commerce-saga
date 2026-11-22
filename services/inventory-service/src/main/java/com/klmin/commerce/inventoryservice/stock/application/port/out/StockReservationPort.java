package com.klmin.commerce.inventoryservice.stock.application.port.out;

import com.klmin.commerce.inventoryservice.stock.domain.StockReservation;

import java.util.Optional;
import java.util.UUID;

public interface StockReservationPort {

    Optional<StockReservation> findByOrderId(UUID orderId);

    /** 같은 orderId 가 이미 있으면 예외 (동시에 들어온 중복 커맨드 중 하나만 커밋된다) */
    void insert(StockReservation reservation);
}
