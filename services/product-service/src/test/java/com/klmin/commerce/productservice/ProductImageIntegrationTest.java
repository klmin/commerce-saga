package com.klmin.commerce.productservice;

import com.klmin.commerce.productservice.product.application.port.in.ChangeProductImageCommand;
import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ManageProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductImageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductImageUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import com.klmin.commerce.productservice.product.application.ProductImageNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 이미지 업로드 → RustFS → 상품 key·관리 이력, 읽기, 시드 이미지 (ADR 0021) */
@SpringBootTest(properties = "management.logging.export.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ProductImageIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("01900000-0000-7000-8001-000000000100");
    // 가장 작은 PNG 시그니처 + 아무 바이트 (형식은 시그니처로만 본다)
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired
    ManageProductUseCase manage;

    @Autowired
    GetProductUseCase query;

    @Autowired
    ProductImageUseCase images;

    @Test
    void upload_storesFile_thenProductPointsToIt_withAudit() {
        ProductResult created = manage.register(new RegisterProductCommand("사진 상품", "", 2_000, ADMIN));

        ProductResult changed = images.changeImage(new ChangeProductImageCommand(created.id(), PNG, ADMIN));

        assertThat(changed.imageKey()).startsWith("products/" + created.id() + "/").endsWith(".png");
        assertThat(query.get(created.id()).imageKey()).isEqualTo(changed.imageKey());
        String fileName = changed.imageKey().substring(changed.imageKey().lastIndexOf('/') + 1);
        ProductImageResult read = images.image(created.id().toString(), fileName);
        assertThat(read.data()).isEqualTo(PNG);
        assertThat(read.contentType()).isEqualTo("image/png");
        assertThat(query.auditLog(created.id(), 0, 1).items().getFirst()).satisfies(a -> {
            assertThat(a.action()).isEqualTo("IMAGE_CHANGED");
            assertThat(a.before().imageKey()).isNull();
            assertThat(a.after().imageKey()).isEqualTo(changed.imageKey());
        });

        // 다시 올리면 새 key (옛 파일은 남는다)
        ProductResult again = images.changeImage(new ChangeProductImageCommand(created.id(), PNG, ADMIN));
        assertThat(again.imageKey()).isNotEqualTo(changed.imageKey());
        assertThat(images.image(created.id().toString(), fileName).data()).isEqualTo(PNG);
    }

    @Test
    void missingOrMalformed_isNotFound() {
        UUID product = UUID.fromString("01900000-0000-7000-8000-000000000001");
        assertThatThrownBy(() -> images.image(product.toString(), "nope.png"))
                .isInstanceOf(ProductImageNotFoundException.class);
        assertThatThrownBy(() -> images.image("..", "x.png")).isInstanceOf(ProductImageNotFoundException.class);
        assertThatThrownBy(() -> images.image(product.toString(), "a.exe"))
                .isInstanceOf(ProductImageNotFoundException.class);
    }

    // 시작할 때 시드 이미지를 백그라운드로 넣는다 (product V5 의 image_key 가 가리키는 파일)
    @Test
    void seedImages_areUploadedOnStartup() throws InterruptedException {
        String keyboard = "01900000-0000-7000-8000-000000000001";
        assertThat(query.get(UUID.fromString(keyboard)).imageKey()).isEqualTo("products/" + keyboard + "/seed.svg");

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            try {
                ProductImageResult seed = images.image(keyboard, "seed.svg");
                assertThat(seed.contentType()).isEqualTo("image/svg+xml");
                assertThat(new String(seed.data())).startsWith("<svg");
                return;
            } catch (ProductImageNotFoundException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
                Thread.sleep(200);
            }
        }
    }
}
