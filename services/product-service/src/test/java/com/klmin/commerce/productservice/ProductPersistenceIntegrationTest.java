package com.klmin.commerce.productservice;

import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.GetProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.ManageProductUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.in.RegisterProductCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway 시드·JPA 매핑·가격 이력·동시 가격 변경 (Postgres) */
@SpringBootTest(properties = "management.logging.export.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ProductPersistenceIntegrationTest {

    @Autowired
    ManageProductUseCase manage;

    @Autowired
    GetProductUseCase query;

    @Test
    void seededProducts_matchFrontendAndInventoryIds() {
        List<ProductResult> seeded = query.findByIds(List.of(1L, 2L, 3L, 4L, 5L, 999L));

        assertThat(seeded).extracting(ProductResult::id).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(seeded.getFirst().price()).isEqualTo(39_000);
    }

    @Test
    void register_startsAt1000_andChangePriceKeepsHistory() {
        ProductResult created = manage.register(new RegisterProductCommand("테스트 상품", "설명", 10_000));
        assertThat(created.id()).isGreaterThanOrEqualTo(1000);

        manage.changePrice(new ChangePriceCommand(created.id(), 12_000, 1000));
        manage.changePrice(new ChangePriceCommand(created.id(), 12_000, 1000));
        manage.changePrice(new ChangePriceCommand(created.id(), 11_000, 1001));

        assertThat(query.get(created.id()).price()).isEqualTo(11_000);
        assertThat(query.priceHistory(created.id()))
                .extracting(h -> h.oldPrice() + "->" + h.newPrice() + "@" + h.changedBy())
                .containsExactly("12000->11000@1001", "10000->12000@1000");
    }

    // 동시 변경이 서로 덮어써도 이력의 old_price 가 직전 가격과 이어진다 (행 잠금)
    @Test
    void concurrentPriceChanges_historyIsContinuous() {
        ProductResult created = manage.register(new RegisterProductCommand("동시성 상품", "", 1_000));
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            long price = 1_000 + i;
            futures.add(CompletableFuture.runAsync(
                    () -> manage.changePrice(new ChangePriceCommand(created.id(), price, 1000))));
        }
        futures.forEach(CompletableFuture::join);

        var history = new ArrayList<>(query.priceHistory(created.id()));
        java.util.Collections.reverse(history);
        assertThat(history).hasSize(10);
        assertThat(history.getFirst().oldPrice()).isEqualTo(1_000);
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).oldPrice()).isEqualTo(history.get(i - 1).newPrice());
        }
        assertThat(query.get(created.id()).price()).isEqualTo(history.getLast().newPrice());
    }

    @Test
    void list_hidesStoppedProducts_adminListShowsThem() {
        ProductResult created = manage.register(new RegisterProductCommand("중지 상품", "", 5_000));
        manage.changeSaleStatus(new com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand(created.id(), false));

        assertThat(query.list(new ListProductsQuery(0, 100, false)).items())
                .extracting(ProductResult::id).doesNotContain(created.id());
        assertThat(query.list(new ListProductsQuery(0, 100, true)).items())
                .extracting(ProductResult::id).contains(created.id());
        assertThat(query.findByIds(List.of(created.id())).getFirst().onSale()).isFalse();
    }
}
