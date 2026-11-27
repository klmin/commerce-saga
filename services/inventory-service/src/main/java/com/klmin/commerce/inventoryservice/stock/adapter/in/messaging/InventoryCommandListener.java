package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.ReturnStock;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReleaseStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReturnStockUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * inventory.command 소비 (ReserveStock, ReleaseStock, ReturnStock). 처리 중 일시 오류는 복구될 때까지 재시도하고,
 * 재시도해도 같은 오류(본문·헤더 이상)는 NonRetryableMessageException 으로 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 같은 메시지의 재전달은 processed_message(id 헤더)로, 내용이 같은 다른 메시지는 주문별 예약 기록(stock_reservations)으로
 * 걸러져 재고가 두 번 잡히거나 풀리지 않는다 (ADR 0006).
 */
@Component
class InventoryCommandListener {

    private final ReserveStockUseCase reserveStockUseCase;
    private final ReleaseStockUseCase releaseStockUseCase;
    private final ReturnStockUseCase returnStockUseCase;
    private final JsonMapper json;

    InventoryCommandListener(ReserveStockUseCase reserveStockUseCase, ReleaseStockUseCase releaseStockUseCase,
                             ReturnStockUseCase returnStockUseCase, JsonMapper json) {
        this.reserveStockUseCase = reserveStockUseCase;
        this.releaseStockUseCase = releaseStockUseCase;
        this.returnStockUseCase = returnStockUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.INVENTORY_COMMAND)
    void onMessage(ConsumerRecord<String, String> record) {
        // 커맨드 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        String type = Messages.type(record);
        switch (type) {
            case ReserveStock.TYPE -> {
                ReserveStock command = Messages.read(json, record, ReserveStock.class);
                Messages.requireOrderId(command.orderId(), record);
                reserveStockUseCase.reserve(Messages.messageId(record),
                        new ReserveStockCommand(command.orderId(), command.lines().stream()
                                .map(l -> new ReserveStockCommand.Line(l.productId(), l.quantity()))
                                .toList()));
            }
            case ReleaseStock.TYPE -> {
                ReleaseStock command = Messages.read(json, record, ReleaseStock.class);
                releaseStockUseCase.release(Messages.messageId(record),
                        Messages.requireOrderId(command.orderId(), record));
            }
            case ReturnStock.TYPE -> {
                ReturnStock command = Messages.read(json, record, ReturnStock.class);
                returnStockUseCase.returnStock(Messages.messageId(record),
                        Messages.requireOrderId(command.orderId(), record));
            }
            case null -> throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
            default -> throw new NonRetryableMessageException(
                    "unknown type " + type + ": " + Messages.describe(record));
        }
    }
}
