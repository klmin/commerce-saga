package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentStatsResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.LoadPaymentStatsPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * 통계는 집계 쿼리로 바로 읽는다. 보정 대상 부분 인덱스(V4)와 같은 조건.
 * UNKNOWN 나이는 결제 기록(= PG 타임아웃) 시각부터 잰다. 취소 요청이 오면 updated_at 이 바뀌어 나이가 줄어들기 때문.
 * 환불 대기 나이는 취소 요청을 받은 시각(updated_at)부터 잰다
 */
@Component
class PaymentStatsPersistenceAdapter implements LoadPaymentStatsPort {

    private static final String STATS = """
            select
              count(*) filter (where status = 'UNKNOWN') as unknown,
              min(created_at) filter (where status = 'UNKNOWN') as oldest_unknown,
              count(*) filter (where status = 'APPROVED' and cancel_requested) as refund_pending,
              min(updated_at) filter (where status = 'APPROVED' and cancel_requested) as oldest_refund_pending
            from payments
            where status = 'UNKNOWN' or (status = 'APPROVED' and cancel_requested)
            """;

    private final JdbcClient jdbc;

    PaymentStatsPersistenceAdapter(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public PaymentStatsResult loadPaymentStats(Instant now) {
        return jdbc.sql(STATS)
                .query((rs, n) -> new PaymentStatsResult(
                        rs.getLong("unknown"), age(rs.getTimestamp("oldest_unknown"), now),
                        rs.getLong("refund_pending"), age(rs.getTimestamp("oldest_refund_pending"), now)))
                .single();
    }

    private static Duration age(Timestamp since, Instant now) {
        if (since == null) {
            return Duration.ZERO;
        }
        Duration age = Duration.between(since.toInstant(), now);
        return age.isNegative() ? Duration.ZERO : age;
    }
}
