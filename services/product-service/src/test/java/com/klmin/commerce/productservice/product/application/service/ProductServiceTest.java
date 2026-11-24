package com.klmin.commerce.productservice.product.application.service;

import com.klmin.commerce.productservice.common.error.DomainValidationException;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import com.klmin.commerce.productservice.product.application.port.out.PriceHistoryPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductPort;
import com.klmin.commerce.productservice.product.domain.PriceChange;
import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductServiceTest {

    private final InMemoryProducts products = new InMemoryProducts();
    private final ProductService service = new ProductService(products, products,
            Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void changePrice_recordsHistory() {
        ProductResult created = service.register(new RegisterProductCommand("키보드", "", 39_000));

        ProductResult changed = service.changePrice(new ChangePriceCommand(created.id(), 35_000, 1000));

        assertThat(changed.price()).isEqualTo(35_000);
        assertThat(products.history).singleElement().satisfies(h -> {
            assertThat(h.oldPrice().value()).isEqualTo(39_000);
            assertThat(h.newPrice().value()).isEqualTo(35_000);
            assertThat(h.changedBy().value()).isEqualTo(1000);
        });
    }

    @Test
    void changePrice_samePrice_noHistory() {
        ProductResult created = service.register(new RegisterProductCommand("키보드", "", 39_000));

        service.changePrice(new ChangePriceCommand(created.id(), 39_000, 1000));

        assertThat(products.history).isEmpty();
    }

    @Test
    void changePrice_unknownProduct_404() {
        assertThatThrownBy(() -> service.changePrice(new ChangePriceCommand(99, 1, 1000)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> service.changePrice(new ChangePriceCommand(0, 1, 1000)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void changePrice_invalidPrice_400_andNothingChanges() {
        ProductResult created = service.register(new RegisterProductCommand("키보드", "", 39_000));

        assertThatThrownBy(() -> service.changePrice(new ChangePriceCommand(created.id(), 0, 1000)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(products.history).isEmpty();
    }

    @Test
    void stopSale() {
        ProductResult created = service.register(new RegisterProductCommand("키보드", "", 39_000));

        ProductResult stopped = service.changeSaleStatus(new ChangeSaleStatusCommand(created.id(), false));

        assertThat(stopped.onSale()).isFalse();
    }

    static final class InMemoryProducts implements ProductPort, PriceHistoryPort {

        final Map<Long, Product> store = new HashMap<>();
        final List<PriceChange> history = new ArrayList<>();
        long seq = 1000;

        @Override
        public Product save(Product product) {
            Product saved = product.id() != null ? product : Product.restore(ProductId.of(seq++), product.name(),
                    product.description(), product.price(), product.onSale(), product.createdAt(),
                    product.updatedAt());
            store.put(saved.id().value(), saved);
            return saved;
        }

        @Override
        public Optional<Product> findByIdForUpdate(ProductId productId) {
            return Optional.ofNullable(store.get(productId.value()));
        }

        @Override
        public void save(PriceChange change) {
            history.add(change);
        }
    }
}
