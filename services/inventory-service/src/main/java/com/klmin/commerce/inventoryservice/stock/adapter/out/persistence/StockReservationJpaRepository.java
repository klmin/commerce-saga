package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface StockReservationJpaRepository extends JpaRepository<StockReservationJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationJpaEntity r where r.orderId = :orderId")
    Optional<StockReservationJpaEntity> findByIdForUpdate(@Param("orderId") UUID orderId);
}
