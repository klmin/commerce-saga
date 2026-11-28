package com.klmin.commerce.productservice.product.adapter.out.storage;

import com.klmin.commerce.productservice.product.domain.ImageFormat;
import com.klmin.commerce.productservice.product.domain.ImageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 시드 상품 이미지를 저장소에 넣는다 (ADR 0021). classpath {@code seed-images/<상품 id>.svg} → key
 * {@code products/<상품 id>/seed.svg} (product V5 시드가 가리키는 key). 버킷이 없으면 만들고, 이미 있는 파일은 건너뛴다.
 * <p>
 * 저장소가 아직 안 떴어도 서비스 시작을 막지 않는다: 백그라운드에서 될 때까지 다시 해 본다(상품·주문은 저장소와 무관).
 * 여러 인스턴스가 같이 해도 같은 key 에 같은 내용이라 결과가 같다
 */
@Component
class ProductImageSeeder {

    private static final Logger log = LoggerFactory.getLogger(ProductImageSeeder.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY = Duration.ofMinutes(1);

    private final S3ProductImageStore store;
    private final boolean enabled;

    ProductImageSeeder(S3ProductImageStore store, ProductImageStoreProperties properties) {
        this.store = store;
        this.enabled = properties.seed();
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedInBackground() {
        if (enabled) {
            Thread.ofVirtual().name("product-image-seeder").start(this::seedUntilDone);
        }
    }

    private void seedUntilDone() {
        Duration wait = FIRST_RETRY;
        while (true) {
            try {
                int uploaded = seed();
                log.info("시드 상품 이미지 확인 끝 (새로 올림 {}개)", uploaded);
                return;
            } catch (RuntimeException | IOException e) {
                log.warn("시드 상품 이미지를 넣지 못함, {}초 뒤 다시: {}", wait.toSeconds(), e.toString());
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            wait = wait.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait.multipliedBy(2);
        }
    }

    /** 올린 개수 */
    int seed() throws IOException {
        store.ensureBucket();
        int uploaded = 0;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:seed-images/*.svg")) {
            String productId = resource.getFilename().replace(".svg", "");
            ImageKey key = ImageKey.fromPath(productId, "seed.svg");
            if (!store.exists(key)) {
                store.put(key, resource.getContentAsByteArray(), ImageFormat.SVG.contentType());
                uploaded++;
            }
        }
        return uploaded;
    }
}
