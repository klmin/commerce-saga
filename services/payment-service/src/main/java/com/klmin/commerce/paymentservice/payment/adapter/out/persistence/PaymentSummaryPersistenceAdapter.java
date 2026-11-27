package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentSummaryResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.LoadPaymentSummariesPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/** 결제 목록은 DTO 로 바로 읽는다. 인덱스 (user_id, id) (V6) */
@Component
class PaymentSummaryPersistenceAdapter implements LoadPaymentSummariesPort {

    private static final String SELECT = """
            select id, order_id, amount, status, reason, created_at, updated_at
            from payments
            where user_id = :userId and (cast(:before as uuid) is null or id < :before)
            order by id desc
            limit :size
            """;

    private final JdbcClient jdbc;

    PaymentSummaryPersistenceAdapter(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<PaymentSummaryResult> findByUser(UUID userId, UUID before, int size) {
        return jdbc.sql(SELECT)
                .param("userId", userId)
                .param("before", before)
                .param("size", size)
                .query((rs, n) -> new PaymentSummaryResult(rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getLong("amount"), rs.getString("status"),
                        rs.getString("reason"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }
}
