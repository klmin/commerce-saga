package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface StockReservationJpaRepository extends JpaRepository<StockReservationJpaEntity, UUID> {
}
