package com.klmin.commerce.productservice.product.application.service;

import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.DescribeProductCommand;
import com.klmin.commerce.productservice.product.application.port.in.ManageProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import com.klmin.commerce.productservice.product.application.port.out.PriceHistoryPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductPort;
import com.klmin.commerce.productservice.product.domain.Money;
import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductId;
import com.klmin.commerce.productservice.product.domain.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 상품 쓰기. 수정은 행을 잠그고(SELECT … FOR UPDATE) 바꾼다.
 * 가격 변경과 이력 저장은 한 트랜잭션이라 이력 없이 가격만 바뀌는 일이 없다.
 */
@Service
class ProductService implements ManageProductUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductPort productPort;
    private final PriceHistoryPort priceHistoryPort;
    private final Clock clock;

    ProductService(ProductPort productPort, PriceHistoryPort priceHistoryPort, Clock clock) {
        this.productPort = productPort;
        this.priceHistoryPort = priceHistoryPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ProductResult register(RegisterProductCommand command) {
        Product saved = productPort.save(Product.register(command.name(), command.description(),
                Money.of(command.price()), clock.instant()));
        log.info("상품 등록 (productId={}, price={})", saved.id().value(), saved.price().value());
        return ProductResult.from(saved);
    }

    @Override
    @Transactional
    public ProductResult describe(DescribeProductCommand command) {
        Product product = load(command.productId());
        product.describe(command.name(), command.description(), clock.instant());
        return ProductResult.from(productPort.save(product));
    }

    @Override
    @Transactional
    public ProductResult changePrice(ChangePriceCommand command) {
        Money newPrice = Money.of(command.price());
        UserId changedBy = UserId.of(command.changedBy());
        Product product = load(command.productId());
        product.changePrice(newPrice, changedBy, clock.instant()).ifPresent(change -> {
            priceHistoryPort.save(change);
            log.info("가격 변경 (productId={}, {} -> {}, by={})", command.productId(),
                    change.oldPrice().value(), change.newPrice().value(), command.changedBy());
        });
        return ProductResult.from(productPort.save(product));
    }

    @Override
    @Transactional
    public ProductResult changeSaleStatus(ChangeSaleStatusCommand command) {
        Product product = load(command.productId());
        product.changeSaleStatus(command.onSale(), clock.instant());
        return ProductResult.from(productPort.save(product));
    }

    private Product load(long productId) {
        if (productId <= 0) {
            throw new ProductNotFoundException(productId);
        }
        return productPort.findByIdForUpdate(ProductId.of(productId))
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
