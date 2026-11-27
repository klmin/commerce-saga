package com.klmin.commerce.productservice.product.adapter.out.persistence;

import com.klmin.commerce.productservice.product.application.port.out.PriceHistoryPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductAuditPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductPort;
import com.klmin.commerce.productservice.product.domain.Money;
import com.klmin.commerce.productservice.product.domain.PriceChange;
import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductChange;
import com.klmin.commerce.productservice.product.domain.ProductId;
import com.klmin.commerce.productservice.product.domain.ProductSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class ProductPersistenceAdapter implements ProductPort, PriceHistoryPort, ProductAuditPort {

    private final ProductJpaRepository productRepository;
    private final PriceHistoryJpaRepository priceHistoryRepository;
    private final ProductAuditLogJpaRepository auditLogRepository;

    ProductPersistenceAdapter(ProductJpaRepository productRepository,
                              PriceHistoryJpaRepository priceHistoryRepository,
                              ProductAuditLogJpaRepository auditLogRepository) {
        this.productRepository = productRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public Product insert(Product product) {
        ProductJpaEntity entity = productRepository.save(new ProductJpaEntity(product.id().value(), product.name(),
                product.description(), product.price().value(), product.onSale(), product.createdAt(),
                product.updatedAt()));
        return toDomain(entity);
    }

    @Override
    public Product save(Product product) {
        // findByIdForUpdate 로 읽은 같은 트랜잭션 안이라 영속성 컨텍스트에 있다
        ProductJpaEntity entity = productRepository.findById(product.id().value()).orElseThrow();
        entity.update(product.name(), product.description(), product.price().value(), product.onSale(),
                product.updatedAt());
        return toDomain(entity);
    }

    @Override
    public Optional<Product> findByIdForUpdate(ProductId productId) {
        return productRepository.findByIdForUpdate(productId.value()).map(ProductPersistenceAdapter::toDomain);
    }

    @Override
    public void save(PriceChange change) {
        priceHistoryRepository.save(new PriceHistoryJpaEntity(change.productId().value(), change.oldPrice().value(),
                change.newPrice().value(), change.changedBy().value(), change.changedAt()));
    }

    @Override
    public void save(ProductChange change) {
        ProductSnapshot before = change.before();
        ProductSnapshot after = change.after();
        auditLogRepository.save(new ProductAuditLogJpaEntity(change.productId().value(), change.action().name(),
                before == null ? null : before.name(),
                before == null ? null : before.description(),
                before == null ? null : before.price().value(),
                before == null ? null : before.onSale(),
                after.name(), after.description(), after.price().value(), after.onSale(),
                change.changedBy().value(), change.changedAt()));
    }

    static Product toDomain(ProductJpaEntity e) {
        return Product.restore(ProductId.of(e.getId()), e.getName(), e.getDescription(), Money.of(e.getPrice()),
                e.isOnSale(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
