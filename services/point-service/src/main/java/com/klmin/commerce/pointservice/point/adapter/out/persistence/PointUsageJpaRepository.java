package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PointUsageJpaRepository extends JpaRepository<PointUsageJpaEntity, UUID> {
}
