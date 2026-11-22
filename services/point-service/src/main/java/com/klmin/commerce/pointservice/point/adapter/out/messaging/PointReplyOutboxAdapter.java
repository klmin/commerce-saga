package com.klmin.commerce.pointservice.point.adapter.out.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.point.PointRefunded;
import com.klmin.commerce.contract.point.PointUseFailed;
import com.klmin.commerce.contract.point.PointUsed;
import com.klmin.commerce.outbox.OutboxWriter;
import com.klmin.commerce.pointservice.point.application.port.out.SendPointReplyPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 포인트 응답을 outbox 에 저장한다. 메시지 키 = orderId */
@Component
class PointReplyOutboxAdapter implements SendPointReplyPort {

    private final OutboxWriter outboxWriter;

    PointReplyOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void pointUsed(UUID orderId) {
        outboxWriter.append(Topics.POINT_REPLY, orderId.toString(), PointUsed.TYPE, new PointUsed(orderId));
    }

    @Override
    public void pointUseFailed(UUID orderId, String reason) {
        outboxWriter.append(Topics.POINT_REPLY, orderId.toString(), PointUseFailed.TYPE,
                new PointUseFailed(orderId, reason));
    }

    @Override
    public void pointRefunded(UUID orderId) {
        outboxWriter.append(Topics.POINT_REPLY, orderId.toString(), PointRefunded.TYPE, new PointRefunded(orderId));
    }
}
