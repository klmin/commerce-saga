package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.stock.application.IdempotencyKeyReusedException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockResult;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.StockAdjustmentPageResult;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockAdjustmentPort;
import com.klmin.commerce.inventoryservice.stock.application.port.out.StockPort;
import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import com.klmin.commerce.inventoryservice.stock.domain.StockAdjustment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 재고 조정 (ADR 0019). 재고 변경 + 조정 기록 = 한 트랜잭션. 다른 서비스에 알릴 일이 없어 메시지는 없다
 * (주문은 예약 때 이 재고를 본다).
 * <p>
 * 순서: 재고 행 확보(없으면 0) → 행 락(예약과 같은 FOR UPDATE) → 키 확인 → 조정 → 기록.
 * 키를 락 뒤에 확인하므로, 같은 키로 동시에 두 번 와도 뒤 요청은 앞이 커밋한 기록을 보고 다시 반영하지 않는다.
 */
@Service
class StockAdjustmentService implements AdjustStockUseCase {

    private final StockPort stockPort;
    private final StockAdjustmentPort adjustmentPort;
    private final Clock clock;

    StockAdjustmentService(StockPort stockPort, StockAdjustmentPort adjustmentPort, Clock clock) {
        this.stockPort = stockPort;
        this.adjustmentPort = adjustmentPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AdjustStockResult adjust(AdjustStockCommand command) {
        ProductId productId = ProductId.of(command.productId());
        Instant now = clock.instant();

        stockPort.createIfAbsent(productId, now);
        Stock stock = stockPort.findAllForUpdate(List.of(productId)).getFirst();

        Optional<StockAdjustment> prior = adjustmentPort.findByKey(command.adminId(), command.idempotencyKey());
        if (prior.isPresent()) {
            if (!prior.get().sameRequest(productId, command.delta(), command.reason())) {
                throw new IdempotencyKeyReusedException();
            }
            return result(stock, prior.get(), true);
        }

        stock.adjust(command.delta(), now);
        stockPort.update(List.of(stock));
        StockAdjustment saved = adjustmentPort.insert(new StockAdjustment(null, productId, command.delta(),
                        stock.available(), command.reason(), command.adminId(), command.idempotencyKey(), now))
                // 같은 키로 다른 상품을 동시에 조정해 앞이 먼저 커밋한 경우. 예외로 이 트랜잭션(재고 변경)을 되돌린다
                .orElseThrow(IdempotencyKeyReusedException::new);
        return result(stock, saved, false);
    }

    @Override
    @Transactional(readOnly = true)
    public StockAdjustmentPageResult adjustments(UUID productId, int page, int size) {
        List<StockAdjustment> rows = adjustmentPort.findByProduct(ProductId.of(productId), page * size, size + 1);
        boolean hasNext = rows.size() > size;
        return new StockAdjustmentPageResult(
                rows.stream().limit(size).map(AdjustStockResult.Adjustment::from).toList(), hasNext);
    }

    private static AdjustStockResult result(Stock stock, StockAdjustment adjustment, boolean replayed) {
        return new AdjustStockResult(stock.productId().value(), stock.available(), stock.reserved(),
                AdjustStockResult.Adjustment.from(adjustment), replayed);
    }
}
