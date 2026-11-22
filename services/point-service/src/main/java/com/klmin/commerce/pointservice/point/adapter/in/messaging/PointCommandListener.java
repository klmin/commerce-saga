package com.klmin.commerce.pointservice.point.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.point.RefundPoint;
import com.klmin.commerce.contract.point.UsePoint;
import com.klmin.commerce.pointservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.pointservice.point.application.port.in.RefundPointUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointCommand;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * point.command 소비 (UsePoint, RefundPoint). 처리 중 일시 오류는 복구될 때까지 재시도하고,
 * 재시도해도 같은 오류(본문·헤더 이상)는 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 중복 커맨드는 주문별 사용 기록(point_usages)으로 걸러져 두 번 차감·환불되지 않는다.
 */
@Component
class PointCommandListener {

    private final UsePointUseCase usePointUseCase;
    private final RefundPointUseCase refundPointUseCase;
    private final JsonMapper json;

    PointCommandListener(UsePointUseCase usePointUseCase, RefundPointUseCase refundPointUseCase, JsonMapper json) {
        this.usePointUseCase = usePointUseCase;
        this.refundPointUseCase = refundPointUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.POINT_COMMAND)
    void onMessage(ConsumerRecord<String, String> record) {
        // 커맨드 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        String type = Messages.type(record);
        switch (type) {
            case UsePoint.TYPE -> {
                UsePoint command = Messages.read(json, record, UsePoint.class);
                usePointUseCase.use(new UsePointCommand(Messages.requireOrderId(command.orderId(), record),
                        command.userId(), command.amount()));
            }
            case RefundPoint.TYPE -> {
                RefundPoint command = Messages.read(json, record, RefundPoint.class);
                refundPointUseCase.refund(Messages.requireOrderId(command.orderId(), record));
            }
            case null -> throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
            default -> throw new NonRetryableMessageException(
                    "unknown type " + type + ": " + Messages.describe(record));
        }
    }
}
