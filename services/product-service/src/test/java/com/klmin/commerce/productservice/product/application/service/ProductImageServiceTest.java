package com.klmin.commerce.productservice.product.application.service;

import com.klmin.commerce.productservice.common.error.DomainValidationException;
import com.klmin.commerce.productservice.product.application.ImageStoreUnavailableException;
import com.klmin.commerce.productservice.product.application.ProductImageNotFoundException;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangeProductImageCommand;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.PriceChangeResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import com.klmin.commerce.productservice.product.application.port.out.LoadProductPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductImageStorePort;
import com.klmin.commerce.productservice.product.domain.ImageKey;
import com.klmin.commerce.productservice.product.domain.ProductAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 이미지 올리기: 저장소에 먼저, 그다음 상품 행·관리 이력. 저장소가 실패하면 상품은 그대로 (ADR 0021) */
class ProductImageServiceTest {

    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9};

    private final ProductServiceTest.InMemoryProducts products = new ProductServiceTest.InMemoryProducts();
    private final ProductService productService = new ProductService(products, products, products, products,
            Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC));
    private final InMemoryStore store = new InMemoryStore();
    private final ProductImageService service = new ProductImageService(store, new Exists(), productService);

    private UUID register() {
        UUID id = productService.register(new RegisterProductCommand("키보드", "", 39_000, ADMIN)).id();
        products.audit.clear();
        return id;
    }

    @Test
    void changeImage_storesFirst_thenProductAndAudit() {
        UUID id = register();

        ProductResult result = service.changeImage(new ChangeProductImageCommand(id, PNG, ADMIN));

        assertThat(result.imageKey()).matches("products/" + id + "/[0-9a-f-]{36}\\.png");
        assertThat(store.files).containsOnlyKeys(result.imageKey());
        assertThat(store.contentTypes.get(result.imageKey())).isEqualTo("image/png");
        assertThat(products.store.get(id).image().value()).isEqualTo(result.imageKey());
        assertThat(products.audit).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo(ProductAction.IMAGE_CHANGED);
            assertThat(a.before().image()).isNull();
            assertThat(a.after().image().value()).isEqualTo(result.imageKey());
        });
    }

    @Test
    void storeDown_503_productUnchanged() {
        UUID id = register();
        store.down = true;

        assertThatThrownBy(() -> service.changeImage(new ChangeProductImageCommand(id, PNG, ADMIN)))
                .isInstanceOf(ImageStoreUnavailableException.class);
        assertThat(products.store.get(id).image()).isNull();
        assertThat(products.audit).isEmpty();
    }

    @Test
    void badFile_or_unknownProduct_storesNothing() {
        UUID id = register();

        assertThatThrownBy(() -> service.changeImage(new ChangeProductImageCommand(id, "<svg/>".getBytes(), ADMIN)))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.changeImage(new ChangeProductImageCommand(
                UUID.fromString("01900000-0000-7000-8000-000000000099"), PNG, ADMIN)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> service.changeImage(new ChangeProductImageCommand(id, PNG, null)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(store.files).isEmpty();
        assertThat(products.audit).isEmpty();
    }

    @Test
    void image_readsByPath_andRejectsOtherPaths() {
        UUID id = register();
        String key = service.changeImage(new ChangeProductImageCommand(id, PNG, ADMIN)).imageKey();
        String fileName = key.substring(key.lastIndexOf('/') + 1);

        assertThat(service.image(id.toString(), fileName).data()).isEqualTo(PNG);
        assertThatThrownBy(() -> service.image(id.toString(), "missing.png"))
                .isInstanceOf(ProductImageNotFoundException.class);
        assertThatThrownBy(() -> service.image("..", fileName)).isInstanceOf(ProductImageNotFoundException.class);
    }

    static final class InMemoryStore implements ProductImageStorePort {

        final Map<String, byte[]> files = new HashMap<>();
        final Map<String, String> contentTypes = new HashMap<>();
        boolean down;

        @Override
        public void put(ImageKey key, byte[] data, String contentType) {
            if (down) {
                throw new ImageStoreUnavailableException(new RuntimeException("down"));
            }
            files.put(key.value(), data);
            contentTypes.put(key.value(), contentType);
        }

        @Override
        public Optional<byte[]> get(ImageKey key) {
            return Optional.ofNullable(files.get(key.value()));
        }
    }

    // 업로드는 상품이 있는지만 본다
    private final class Exists implements LoadProductPort {

        @Override
        public boolean exists(UUID productId) {
            return products.store.containsKey(productId);
        }

        @Override
        public Optional<ProductResult> findById(UUID productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProductPageResult list(ListProductsQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProductResult> findByIds(Collection<UUID> productIds) {
            return new ArrayList<>();
        }

        @Override
        public List<PriceChangeResult> priceHistory(UUID productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProductAuditPageResult auditLog(UUID productId, int page, int size) {
            throw new UnsupportedOperationException();
        }
    }
}
