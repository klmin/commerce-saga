package com.klmin.commerce.productservice;

import com.klmin.commerce.productservice.product.application.port.in.ChangePriceCommand;
import com.klmin.commerce.productservice.product.application.port.in.ChangeSaleStatusCommand;
import com.klmin.commerce.productservice.product.application.port.in.DescribeProductCommand;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditResult;
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
import java.util.UUID;
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

    // V2 시드 (inventory 재고 시드와 같은 고정 UUID, ADR 0018)
    private static UUID seed(int n) {
        return UUID.fromString("01900000-0000-7000-8000-00000000000" + n);
    }

    @Test
    void seededProducts_matchFrontendAndInventoryIds() {
        List<ProductResult> seeded = query.findByIds(
                List.of(seed(1), seed(2), seed(3), seed(4), seed(5),
                        UUID.fromString("01900000-0000-7000-8000-000000000999")));

        assertThat(seeded).extracting(ProductResult::id).containsExactly(seed(1), seed(2), seed(3), seed(4), seed(5));
        assertThat(seeded.getFirst().price()).isEqualTo(39_000);
    }

    @Test
    void register_assignsUuidV7_andChangePriceKeepsHistory() {
        ProductResult created = manage.register(new RegisterProductCommand("테스트 상품", "설명", 10_000, 1000));
        assertThat(created.id().version()).isEqualTo(7);

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
        ProductResult created = manage.register(new RegisterProductCommand("동시성 상품", "", 1_000, 1000));
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

    // 등록·정보 수정·가격·판매 상태가 누가·전후 값과 함께 최근부터 남고, 같은 값 요청은 남지 않는다
    @Test
    void auditLog_recordsEveryChangeNewestFirst() {
        ProductResult created = manage.register(new RegisterProductCommand("이력 상품", "", 7_000, 1000));
        manage.describe(new DescribeProductCommand(created.id(), "이력 상품 2", "설명", 1001));
        manage.changePrice(new ChangePriceCommand(created.id(), 8_000, 1002));
        manage.changePrice(new ChangePriceCommand(created.id(), 8_000, 1002));
        manage.changeSaleStatus(new ChangeSaleStatusCommand(created.id(), false, 1003));

        var page = query.auditLog(created.id(), 0, 3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).extracting(ProductAuditResult::action, ProductAuditResult::changedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SALE_STATUS_CHANGED", 1003L),
                        org.assertj.core.groups.Tuple.tuple("PRICE_CHANGED", 1002L),
                        org.assertj.core.groups.Tuple.tuple("DESCRIBED", 1001L));
        ProductAuditResult price = page.items().get(1);
        assertThat(price.before()).isEqualTo(new ProductAuditResult.Snapshot("이력 상품 2", "설명", 7_000L, true));
        assertThat(price.after().price()).isEqualTo(8_000L);

        var last = query.auditLog(created.id(), 1, 3);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.items()).singleElement().satisfies(r -> {
            assertThat(r.action()).isEqualTo("REGISTERED");
            assertThat(r.before()).isNull();
            assertThat(r.after().name()).isEqualTo("이력 상품");
        });
    }

    @Test
    void list_hidesStoppedProducts_adminListShowsThem() {
        ProductResult created = manage.register(new RegisterProductCommand("중지 상품", "", 5_000, 1000));
        manage.changeSaleStatus(new ChangeSaleStatusCommand(created.id(), false, 1000));

        assertThat(query.list(new ListProductsQuery(0, 100, false)).items())
                .extracting(ProductResult::id).doesNotContain(created.id());
        assertThat(query.list(new ListProductsQuery(0, 100, true)).items())
                .extracting(ProductResult::id).contains(created.id());
        assertThat(query.findByIds(List.of(created.id())).getFirst().onSale()).isFalse();
    }
}
