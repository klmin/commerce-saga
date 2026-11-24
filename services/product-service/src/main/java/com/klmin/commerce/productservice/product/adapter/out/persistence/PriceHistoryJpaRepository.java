package com.klmin.commerce.productservice.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface PriceHistoryJpaRepository extends JpaRepository<PriceHistoryJpaEntity, Long> {

    List<PriceHistoryJpaEntity> findByProductIdOrderByChangedAtDescIdDesc(long productId);
}
