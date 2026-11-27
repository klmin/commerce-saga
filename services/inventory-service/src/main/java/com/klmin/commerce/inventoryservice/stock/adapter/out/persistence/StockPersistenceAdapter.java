package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import com.klmin.commerce.inventoryservice.stock.application.port.out.StockPort;
import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
class StockPersistenceAdapter implements StockPort {

    private final StockJpaRepository repository;

    StockPersistenceAdapter(StockJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Stock> findById(ProductId productId) {
        return repository.findById(productId.value()).map(StockPersistenceAdapter::toDomain);
    }

    @Override
    public List<Stock> findAllForUpdate(Collection<ProductId> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return repository.findAllForUpdate(productIds.stream().map(ProductId::value).toList()).stream().map(StockPersistenceAdapter::toDomain).toList();
    }

    // 같은 트랜잭션에서 잠가 조회한 엔티티(1차 캐시)에 값만 옮긴다. 커밋 때 더티 체킹으로 UPDATE
    @Override
    public void update(Collection<Stock> stocks) {
        for (Stock stock : stocks) {
            StockJpaEntity entity = repository.findById(stock.productId().value())
                    .orElseThrow(() -> new IllegalStateException("stock not found: " + stock.productId()));
            entity.change(stock.available(), stock.reserved(), stock.updatedAt());
        }
    }

    @Override
    public void createIfAbsent(ProductId productId, Instant now) {
        repository.insertIfAbsent(productId.value(), now);
    }

    private static Stock toDomain(StockJpaEntity e) {
        return Stock.restore(ProductId.of(e.getProductId()), e.getAvailable(), e.getReserved(), e.getUpdatedAt());
    }
}
