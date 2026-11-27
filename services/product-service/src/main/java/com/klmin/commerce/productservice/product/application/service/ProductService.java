package com.klmin.commerce.productservice.product.application.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.productservice.common.error.DomainValidationException;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.DescribeProductCommand;
import com.klmin.commerce.productservice.product.application.port.in.ManageProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import com.klmin.commerce.productservice.product.application.port.out.PriceHistoryPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductAuditPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductPort;
import com.klmin.commerce.productservice.product.domain.Money;
import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductAction;
import com.klmin.commerce.productservice.product.domain.ProductChange;
import com.klmin.commerce.productservice.product.domain.ProductId;
import com.klmin.commerce.productservice.product.domain.ProductSnapshot;
import com.klmin.commerce.productservice.product.domain.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 상품 쓰기. 수정은 행을 잠그고(SELECT … FOR UPDATE) 바꾼다.
 * 상품 변경과 관리 이력(product_audit_log)·가격 이력 저장은 한 트랜잭션이라 이력 없이 상품만 바뀌는 일이 없다.
 * 값이 그대로인 요청은 이력을 남기지 않는다
 */
@Service
class ProductService implements ManageProductUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductPort productPort;
    private final PriceHistoryPort priceHistoryPort;
    private final ProductAuditPort auditPort;
    private final Clock clock;

    ProductService(ProductPort productPort, PriceHistoryPort priceHistoryPort, ProductAuditPort auditPort,
                   Clock clock) {
        this.productPort = productPort;
        this.priceHistoryPort = priceHistoryPort;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ProductResult register(RegisterProductCommand command) {
        UserId changedBy = UserId.of(command.changedBy());
        Instant now = clock.instant();
        Product saved = productPort.insert(Product.register(ProductId.of(UuidCreator.getTimeOrderedEpoch()),
                command.name(), command.description(), Money.of(command.price()), now));
        auditPort.save(ProductChange.registered(saved, changedBy, now));
        log.info("상품 등록 (productId={}, price={}, by={})", saved.id().value(), saved.price().value(),
                command.changedBy());
        return ProductResult.from(saved);
    }

    @Override
    @Transactional
    public ProductResult describe(DescribeProductCommand command) {
        UserId changedBy = UserId.of(command.changedBy());
        Product product = load(command.productId());
        ProductSnapshot before = product.snapshot();
        Instant now = clock.instant();
        product.describe(command.name(), command.description(), now);
        ProductChange.between(product, ProductAction.DESCRIBED, before, changedBy, now).ifPresent(auditPort::save);
        return ProductResult.from(productPort.save(product));
    }

    @Override
    @Transactional
    public ProductResult changePrice(ChangePriceCommand command) {
        Money newPrice = Money.of(command.price());
        UserId changedBy = UserId.of(command.changedBy());
        Product product = load(command.productId());
        ProductSnapshot before = product.snapshot();
        Instant now = clock.instant();
        product.changePrice(newPrice, changedBy, now).ifPresent(change -> {
            priceHistoryPort.save(change);
            log.info("가격 변경 (productId={}, {} -> {}, by={})", command.productId(),
                    change.oldPrice().value(), change.newPrice().value(), command.changedBy());
        });
        ProductChange.between(product, ProductAction.PRICE_CHANGED, before, changedBy, now)
                .ifPresent(auditPort::save);
        return ProductResult.from(productPort.save(product));
    }

    @Override
    @Transactional
    public ProductResult changeSaleStatus(ChangeSaleStatusCommand command) {
        UserId changedBy = UserId.of(command.changedBy());
        Product product = load(command.productId());
        ProductSnapshot before = product.snapshot();
        Instant now = clock.instant();
        product.changeSaleStatus(command.onSale(), now);
        ProductChange.between(product, ProductAction.SALE_STATUS_CHANGED, before, changedBy, now)
                .ifPresent(change -> {
                    auditPort.save(change);
                    log.info("판매 상태 변경 (productId={}, onSale={}, by={})", command.productId(),
                            command.onSale(), command.changedBy());
                });
        return ProductResult.from(productPort.save(product));
    }

    private Product load(UUID productId) {
        if (productId == null) {
            throw new DomainValidationException("productId must not be null");
        }
        return productPort.findByIdForUpdate(ProductId.of(productId))
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
