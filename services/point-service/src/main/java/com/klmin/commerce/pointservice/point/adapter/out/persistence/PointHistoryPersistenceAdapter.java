package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;
import com.klmin.commerce.pointservice.point.application.port.out.LoadPointHistoryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 포인트 내역은 DTO 로 바로 읽는다. 사용 기록(point_usages, 커서 = 주문 id)과 지급 기록(point_grants, 커서 = 지급 id)을
 * 각각 최신 size 개씩 읽어 합친 뒤 다시 size 개. 둘 다 UUIDv7 이라 id 순서가 시간 순서다(PostgreSQL uuid 는 바이트 순 비교).
 * 주문 id 는 order-service, 지급 id 는 이 서비스가 만들어 시계가 조금 다를 수 있지만, 커서도 같은 id 라 쪽 사이에 빠지거나
 * 겹치는 항목은 없다. 인덱스 (user_id, order_id)·(user_id, id) (V5)
 */
@Component
class PointHistoryPersistenceAdapter implements LoadPointHistoryPort {

    private static final String SELECT = """
            select entry_id, kind, order_id, amount, status, reason, created_at, updated_at from (
                (select order_id as entry_id, 'ORDER' as kind, order_id, amount, status, reason,
                        created_at, updated_at
                 from point_usages
                 where user_id = :userId and (cast(:before as uuid) is null or order_id < :before)
                 order by order_id desc
                 limit :size)
                union all
                (select id, type, cast(null as uuid), amount, 'GRANTED', cast(null as varchar), created_at, created_at
                 from point_grants
                 where user_id = :userId and (cast(:before as uuid) is null or id < :before)
                 order by id desc
                 limit :size)
            ) history
            order by entry_id desc
            limit :size
            """;

    private final JdbcClient jdbc;

    PointHistoryPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PointHistoryEntryResult> findByUser(long userId, UUID before, int size) {
        return jdbc.sql(SELECT)
                .param("userId", userId)
                .param("before", before)
                .param("size", size)
                .query((rs, n) -> new PointHistoryEntryResult(rs.getObject("entry_id", UUID.class),
                        rs.getString("kind"), rs.getObject("order_id", UUID.class), rs.getLong("amount"),
                        rs.getString("status"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }
}
