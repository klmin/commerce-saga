package com.klmin.commerce.productservice.product.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Slice<ProductJpaEntity> findByOnSaleTrueOrderByIdAsc(Pageable pageable);

    Slice<ProductJpaEntity> findAllByOrderByIdAsc(Pageable pageable);

    List<ProductJpaEntity> findByIdInOrderByIdAsc(Collection<UUID> ids);
}
