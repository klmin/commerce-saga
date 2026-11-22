package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * inventory.command 소비. 처리 중 일시 오류는 복구될 때까지 재시도하고,
 * 재시도해도 같은 오류(본문·헤더 이상)는 NonRetryableMessageException 으로 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 중복 커맨드는 주문별 예약 기록(stock_reservations)으로 걸러져 재고가 두 번 잡히지 않는다.
 * (메시지 단위 멱등 이력은 3단계)
 */
@Component
class InventoryCommandListener {

    private final ReserveStockUseCase reserveStockUseCase;
    private final JsonMapper json;

    InventoryCommandListener(ReserveStockUseCase reserveStockUseCase, JsonMapper json) {
        this.reserveStockUseCase = reserveStockUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.INVENTORY_COMMAND)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = header(record, MessageHeaders.TYPE);
        // 커맨드 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        if (!ReserveStock.TYPE.equals(type)) {
            throw new NonRetryableMessageException("unknown type " + type + " (topic=" + record.topic()
                    + ", partition=" + record.partition() + ", offset=" + record.offset() + ")");
        }
        // 본문이 비었거나 orderId 가 없으면 재시도해도 같으므로 재시도 불가 오류로 분류한다
        ReserveStock command = record.value() == null ? null : json.readValue(record.value(), ReserveStock.class);
        if (command == null || command.orderId() == null) {
            throw new NonRetryableMessageException("invalid ReserveStock (topic=" + record.topic()
                    + ", partition=" + record.partition() + ", offset=" + record.offset() + ")");
        }
        reserveStockUseCase.reserve(new ReserveStockCommand(command.orderId(), command.lines().stream()
                .map(l -> new ReserveStockCommand.Line(l.productId(), l.quantity()))
                .toList()));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
