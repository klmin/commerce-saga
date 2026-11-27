package com.klmin.commerce.productservice.product.application.service;

import com.klmin.commerce.productservice.common.error.DomainValidationException;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.DescribeProductCommand;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import com.klmin.commerce.productservice.product.application.port.out.PriceHistoryPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductAuditPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductPort;
import com.klmin.commerce.productservice.product.domain.Money;
import com.klmin.commerce.productservice.product.domain.PriceChange;
import com.klmin.commerce.productservice.product.domain.Product;
import com.klmin.commerce.productservice.product.domain.ProductAction;
import com.klmin.commerce.productservice.product.domain.ProductChange;
import com.klmin.commerce.productservice.product.domain.ProductId;
import com.klmin.commerce.productservice.product.domain.ProductSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductServiceTest {

    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");
    private static final UUID OTHER_ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000101");

    private final InMemoryProducts products = new InMemoryProducts();
    private final ProductService service = new ProductService(products, products, products,
            Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));

    private ProductResult registerKeyboard() {
        ProductResult created = service.register(new RegisterProductCommand("키보드", "", 39_000, ADMIN));
        products.audit.clear();
        return created;
    }

    @Test
    void register_recordsAuditWithoutBefore() {
        ProductResult created = service.register(new RegisterProductCommand("키보드", "무선", 39_000, ADMIN));

        assertThat(created.id().version()).as("UUIDv7").isEqualTo(7);
        assertThat(products.audit).singleElement().satisfies(a -> {
            assertThat(a.productId().value()).isEqualTo(created.id());
            assertThat(a.action()).isEqualTo(ProductAction.REGISTERED);
            assertThat(a.before()).isNull();
            assertThat(a.after()).isEqualTo(new ProductSnapshot("키보드", "무선", Money.of(39_000), true));
            assertThat(a.changedBy().value()).isEqualTo(ADMIN);
        });
    }

    @Test
    void changePrice_recordsPriceHistoryAndAudit() {
        ProductResult created = registerKeyboard();

        ProductResult changed = service.changePrice(new ChangePriceCommand(created.id(), 35_000, OTHER_ADMIN));

        assertThat(changed.price()).isEqualTo(35_000);
        assertThat(products.history).singleElement().satisfies(h -> {
            assertThat(h.oldPrice().value()).isEqualTo(39_000);
            assertThat(h.newPrice().value()).isEqualTo(35_000);
            assertThat(h.changedBy().value()).isEqualTo(OTHER_ADMIN);
        });
        assertThat(products.audit).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo(ProductAction.PRICE_CHANGED);
            assertThat(a.before().price().value()).isEqualTo(39_000);
            assertThat(a.after().price().value()).isEqualTo(35_000);
            assertThat(a.changedBy().value()).isEqualTo(OTHER_ADMIN);
        });
    }

    @Test
    void sameValues_noHistory() {
        ProductResult created = registerKeyboard();

        service.changePrice(new ChangePriceCommand(created.id(), 39_000, ADMIN));
        service.describe(new DescribeProductCommand(created.id(), " 키보드 ", null, ADMIN));
        service.changeSaleStatus(new ChangeSaleStatusCommand(created.id(), true, ADMIN));

        assertThat(products.history).isEmpty();
        assertThat(products.audit).isEmpty();
    }

    @Test
    void describe_recordsBeforeAndAfter() {
        ProductResult created = registerKeyboard();

        service.describe(new DescribeProductCommand(created.id(), "기계식 키보드", "청축", ADMIN));

        assertThat(products.audit).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo(ProductAction.DESCRIBED);
            assertThat(a.before().name()).isEqualTo("키보드");
            assertThat(a.after().name()).isEqualTo("기계식 키보드");
            assertThat(a.after().description()).isEqualTo("청축");
        });
    }

    @Test
    void stopSale_recordsAudit() {
        ProductResult created = registerKeyboard();

        ProductResult stopped = service.changeSaleStatus(new ChangeSaleStatusCommand(created.id(), false, ADMIN));

        assertThat(stopped.onSale()).isFalse();
        assertThat(products.audit).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo(ProductAction.SALE_STATUS_CHANGED);
            assertThat(a.before().onSale()).isTrue();
            assertThat(a.after().onSale()).isFalse();
        });
    }

    @Test
    void changePrice_unknownProduct_404() {
        assertThatThrownBy(() -> service.changePrice(new ChangePriceCommand(
                UUID.fromString("01900000-0000-7000-8000-000000000099"), 1, ADMIN)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> service.changePrice(new ChangePriceCommand(null, 1, ADMIN)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void invalidInput_400_andNothingRecorded() {
        ProductResult created = registerKeyboard();

        assertThatThrownBy(() -> service.changePrice(new ChangePriceCommand(created.id(), 0, ADMIN)))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.describe(new DescribeProductCommand(created.id(), " ", "", ADMIN)))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.changeSaleStatus(new ChangeSaleStatusCommand(created.id(), false, null)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(products.history).isEmpty();
        assertThat(products.audit).isEmpty();
    }

    static final class InMemoryProducts implements ProductPort, PriceHistoryPort, ProductAuditPort {

        final Map<UUID, Product> store = new HashMap<>();
        final List<PriceChange> history = new ArrayList<>();
        final List<ProductChange> audit = new ArrayList<>();
        @Override
        public Product insert(Product product) {
            store.put(product.id().value(), product);
            return product;
        }

        @Override
        public Product save(Product product) {
            store.put(product.id().value(), product);
            return product;
        }

        @Override
        public Optional<Product> findByIdForUpdate(ProductId productId) {
            return Optional.ofNullable(store.get(productId.value()));
        }

        @Override
        public void save(PriceChange change) {
            history.add(change);
        }

        @Override
        public void save(ProductChange change) {
            audit.add(change);
        }
    }
}
