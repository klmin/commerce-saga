package com.klmin.commerce.productservice.product.adapter.out.persistence;

import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.PriceChangeResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.out.LoadProductPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class ProductQueryPersistenceAdapter implements LoadProductPort {

    private final ProductJpaRepository productRepository;
    private final PriceHistoryJpaRepository priceHistoryRepository;
    private final ProductAuditLogJpaRepository auditLogRepository;

    ProductQueryPersistenceAdapter(ProductJpaRepository productRepository,
                                   PriceHistoryJpaRepository priceHistoryRepository,
                                   ProductAuditLogJpaRepository auditLogRepository) {
        this.productRepository = productRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public ProductAuditPageResult auditLog(UUID productId, int page, int size) {
        Slice<ProductAuditLogJpaEntity> slice =
                auditLogRepository.findByProductIdOrderByChangedAtDescIdDesc(productId, PageRequest.of(page, size));
        return new ProductAuditPageResult(slice.getContent().stream().map(e -> new ProductAuditResult(
                e.getAction(),
                snapshot(e.getBeforeName(), e.getBeforeDescription(), e.getBeforePrice(), e.getBeforeOnSale(),
                        e.getBeforeCategoryCode()),
                snapshot(e.getAfterName(), e.getAfterDescription(), e.getAfterPrice(), e.getAfterOnSale(),
                        e.getAfterCategoryCode()),
                e.getChangedBy(), e.getChangedAt())).toList(), slice.hasNext());
    }

    /** 값이 하나도 없으면(등록의 before) null */
    private static ProductAuditResult.Snapshot snapshot(String name, String description, Long price,
                                                        Boolean onSale, String categoryCode) {
        if (name == null && description == null && price == null && onSale == null && categoryCode == null) {
            return null;
        }
        return new ProductAuditResult.Snapshot(name, description, price, onSale, categoryCode);
    }

    @Override
    public Optional<ProductResult> findById(UUID productId) {
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
    public List<ProductResult> findByIds(Collection<UUID> productIds) {
        return productRepository.findByIdInOrderByIdAsc(productIds).stream()
                .map(ProductQueryPersistenceAdapter::toResult)
                .toList();
    }

    @Override
    public boolean exists(UUID productId) {
        return productRepository.existsById(productId);
    }

    @Override
    public List<PriceChangeResult> priceHistory(UUID productId) {
        return priceHistoryRepository.findByProductIdOrderByChangedAtDescIdDesc(productId).stream()
                .map(h -> new PriceChangeResult(h.getOldPrice(), h.getNewPrice(), h.getChangedBy(),
                        h.getChangedAt()))
                .toList();
    }

    private static ProductResult toResult(ProductJpaEntity e) {
        return new ProductResult(e.getId(), e.getName(), e.getDescription(), e.getPrice(), e.isOnSale(),
                e.getCategoryCode(), e.getUpdatedAt());
    }
}
