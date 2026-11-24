package com.klmin.commerce.productservice.product.adapter.out.persistence;

import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.PriceChangeResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.out.LoadProductPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
class ProductQueryPersistenceAdapter implements LoadProductPort {

    private final ProductJpaRepository productRepository;
    private final PriceHistoryJpaRepository priceHistoryRepository;

    ProductQueryPersistenceAdapter(ProductJpaRepository productRepository,
                                   PriceHistoryJpaRepository priceHistoryRepository) {
        this.productRepository = productRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    @Override
    public Optional<ProductResult> findById(long productId) {
        return productRepository.findById(productId).map(ProductQueryPersistenceAdapter::toResult);
    }

    @Override
    public ProductPageResult list(ListProductsQuery query) {
        PageRequest pageable = PageRequest.of(query.page(), query.size());
        Slice<ProductJpaEntity> slice = query.includeStopped()
                ? productRepository.findAllByOrderByIdAsc(pageable)
                : productRepository.findByOnSaleTrueOrderByIdAsc(pageable);
        return new ProductPageResult(slice.getContent().stream().map(ProductQueryPersistenceAdapter::toResult)
                .toList(), slice.hasNext());
    }

    @Override
    public List<ProductResult> findByIds(Collection<Long> productIds) {
        return productRepository.findByIdInOrderByIdAsc(productIds).stream()
                .map(ProductQueryPersistenceAdapter::toResult)
                .toList();
    }

    @Override
    public boolean exists(long productId) {
        return productRepository.existsById(productId);
    }

    @Override
    public List<PriceChangeResult> priceHistory(long productId) {
        return priceHistoryRepository.findByProductIdOrderByChangedAtDescIdDesc(productId).stream()
                .map(h -> new PriceChangeResult(h.getOldPrice(), h.getNewPrice(), h.getChangedBy(),
                        h.getChangedAt()))
                .toList();
    }

    private static ProductResult toResult(ProductJpaEntity e) {
        return new ProductResult(e.getId(), e.getName(), e.getDescription(), e.getPrice(), e.isOnSale(),
                e.getUpdatedAt());
    }
}
