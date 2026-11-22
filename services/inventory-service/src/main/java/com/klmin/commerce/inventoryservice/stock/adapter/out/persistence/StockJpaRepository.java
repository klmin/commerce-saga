package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface StockJpaRepository extends JpaRepository<StockJpaEntity, Long> {

    // SELECT ... FOR UPDATE. productId 순서로 잠가서 두 주문이 서로의 락을 기다리는 교착을 피한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockJpaEntity s where s.productId in :productIds order by s.productId")
    List<StockJpaEntity> findAllForUpdate(@Param("productIds") Collection<Long> productIds);
}
