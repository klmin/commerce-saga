package com.klmin.commerce.productservice.product.adapter.out.persistence;

import com.klmin.commerce.productservice.product.application.port.in.CategoryResult;
import com.klmin.commerce.productservice.product.application.port.out.CategoryPort;
import com.klmin.commerce.productservice.product.domain.CategoryCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class CategoryPersistenceAdapter implements CategoryPort {

    private final CategoryJpaRepository repository;

    CategoryPersistenceAdapter(CategoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean exists(CategoryCode code) {
        return repository.existsById(code.value());
    }

    @Override
    public List<CategoryResult> findAll() {
        return repository.findAllByOrderBySortOrderAscCodeAsc().stream()
                .map(c -> new CategoryResult(c.getCode(), c.getName()))
                .toList();
    }
}
