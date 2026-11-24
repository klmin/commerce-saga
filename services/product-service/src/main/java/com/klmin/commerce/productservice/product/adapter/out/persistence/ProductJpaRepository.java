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

interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> findByIdForUpdate(@Param("id") long id);

    Slice<ProductJpaEntity> findByOnSaleTrueOrderByIdAsc(Pageable pageable);

    Slice<ProductJpaEntity> findAllByOrderByIdAsc(Pageable pageable);

    List<ProductJpaEntity> findByIdInOrderByIdAsc(Collection<Long> ids);
}
