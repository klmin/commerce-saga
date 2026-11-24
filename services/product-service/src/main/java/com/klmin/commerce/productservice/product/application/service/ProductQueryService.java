package com.klmin.commerce.productservice.product.application.service;

import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.PriceChangeResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.out.LoadProductPort;
import com.klmin.commerce.productservice.common.error.DomainValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
class ProductQueryService implements GetProductUseCase {

    private final LoadProductPort loadProductPort;

    ProductQueryService(LoadProductPort loadProductPort) {
        this.loadProductPort = loadProductPort;
    }

    @Override
    public ProductResult get(long productId) {
        return loadProductPort.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }

    @Override
    public ProductPageResult list(ListProductsQuery query) {
        if (query.page() < 0 || query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw new DomainValidationException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        return loadProductPort.list(query);
    }

    @Override
    public List<ProductResult> findByIds(List<Long> productIds) {
        if (productIds == null || productIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new DomainValidationException("ids must be positive");
        }
        Set<Long> ids = new LinkedHashSet<>(productIds);
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new DomainValidationException("ids must have 1 to " + MAX_IDS + " distinct values");
        }
        return loadProductPort.findByIds(ids);
    }

    @Override
    public List<PriceChangeResult> priceHistory(long productId) {
        if (!loadProductPort.exists(productId)) {
            throw new ProductNotFoundException(productId);
        }
        return loadProductPort.priceHistory(productId);
    }
}
