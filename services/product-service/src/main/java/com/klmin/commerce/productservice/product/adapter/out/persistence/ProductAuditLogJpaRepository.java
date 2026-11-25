package com.klmin.commerce.productservice.product.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductAuditLogJpaRepository extends JpaRepository<ProductAuditLogJpaEntity, Long> {

    Slice<ProductAuditLogJpaEntity> findByProductIdOrderByChangedAtDescIdDesc(long productId, Pageable pageable);
}
