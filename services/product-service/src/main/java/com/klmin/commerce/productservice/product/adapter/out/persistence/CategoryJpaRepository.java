package com.klmin.commerce.productservice.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, String> {

    List<CategoryJpaEntity> findAllByOrderBySortOrderAscCodeAsc();
}
