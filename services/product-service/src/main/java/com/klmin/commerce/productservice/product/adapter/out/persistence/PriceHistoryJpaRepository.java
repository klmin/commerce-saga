package com.klmin.commerce.productservice.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface PriceHistoryJpaRepository extends JpaRepository<PriceHistoryJpaEntity, Long> {

    List<PriceHistoryJpaEntity> findByProductIdOrderByChangedAtDescIdDesc(UUID productId);
}
