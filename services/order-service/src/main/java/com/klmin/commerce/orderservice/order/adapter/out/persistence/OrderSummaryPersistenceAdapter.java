package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.in.OrderSummaryResult;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderSummariesPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 주문 목록은 DTO 로 바로 읽는다 (조회는 애그리거트를 거치지 않음). 주문 한 쪽 + 그 주문들의 줄을 한 번에 = 쿼리 2개.
 * 인덱스 (user_id, id) (V6) 로 사용자 주문을 id 내림차순으로 읽는다.
 */
@Component
class OrderSummaryPersistenceAdapter implements LoadOrderSummariesPort {

    private static final String ORDERS = """
            select id, status, total_amount, point_amount, cancel_reason, created_at, updated_at
            from orders
            where user_id = :userId and (cast(:before as uuid) is null or id < :before)
            order by id desc
            limit :size
            """;

    private static final String LINES = """
            select order_id, product_id, quantity, unit_price
            from order_lines
            where order_id = any(:orderIds)
            order by order_id, line_no
            """;

    private final JdbcClient jdbc;

    OrderSummaryPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OrderSummaryResult> findByUser(long userId, UUID before, int size) {
        List<Row> rows = jdbc.sql(ORDERS)
                .param("userId", userId)
                .param("before", before)
                .param("size", size)
                .query(OrderSummaryPersistenceAdapter::row)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OrderSummaryResult.Line>> lines = new HashMap<>();
        jdbc.sql(LINES)
                .param("orderIds", rows.stream().map(Row::id).toArray(UUID[]::new))
                .query(rs -> {
                    lines.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                            .add(new OrderSummaryResult.Line(rs.getLong("product_id"), rs.getInt("quantity"),
                                    rs.getLong("unit_price")));
                });
        return rows.stream()
                .map(r -> new OrderSummaryResult(r.id(), r.status(), r.totalAmount(), r.pointAmount(), r.cancelReason(),
                        lines.getOrDefault(r.id(), List.of()), r.createdAt(), r.updatedAt()))
                .toList();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("total_amount"),
                rs.getLong("point_amount"), rs.getString("cancel_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private record Row(UUID id, String status, long totalAmount, long pointAmount, String cancelReason,
                       Instant createdAt, Instant updatedAt) {
    }
}
