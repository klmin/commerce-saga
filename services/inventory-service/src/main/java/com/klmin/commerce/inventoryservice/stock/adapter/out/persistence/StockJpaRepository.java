package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface StockJpaRepository extends JpaRepository<StockJpaEntity, UUID> {

    // SELECT ... FOR UPDATE. productId 순서(uuid 는 바이트 순이라 어느 트랜잭션이든 같은 순서)로 잠가서 두 주문이 서로의 락을 기다리는 교착을 피한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockJpaEntity s where s.productId in :productIds order by s.productId")
    List<StockJpaEntity> findAllForUpdate(@Param("productIds") Collection<UUID> productIds);

    // 없을 때만 0 으로. 동시에 같은 행을 넣으면 뒤 INSERT 는 앞 트랜잭션이 끝날 때까지 기다렸다가 건너뛴다
    @Modifying
    @Query(value = """
            insert into stocks (product_id, available, reserved, updated_at) values (:productId, 0, 0, :now)
            on conflict (product_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("productId") UUID productId, @Param("now") Instant now);
}
