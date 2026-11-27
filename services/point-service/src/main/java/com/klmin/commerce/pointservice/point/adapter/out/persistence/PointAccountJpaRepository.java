package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface PointAccountJpaRepository extends JpaRepository<PointAccountJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PointAccountJpaEntity a where a.userId = :userId")
    Optional<PointAccountJpaEntity> findByIdForUpdate(@Param("userId") UUID userId);
}
