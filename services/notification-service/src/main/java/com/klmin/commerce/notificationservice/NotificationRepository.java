package com.klmin.commerce.notificationservice;

import com.github.f4b6a3.uuid.UuidCreator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class NotificationRepository {

    static final int MAX_PAGE_SIZE = 50;

    private final JdbcClient jdbc;

    NotificationRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * id 는 저장 전에 UUIDv7 로 만든다 (ADR 0018). 시간순이라 id 순 = 최신순이고, 커서 페이징이 id 로 그대로 된다.
     *
     * @return 새로 만들었으면 true, 같은 주문·종류의 알림이 이미 있으면 false (중복 이벤트)
     */
    boolean insertIfAbsent(UUID orderId, UUID userId, String type, String message) {
        return jdbc.sql("""
                        insert into notifications (id, order_id, user_id, type, message)
                        values (?, ?, ?, ?, ?)
                        on conflict (order_id, type) do nothing
                        """)
                .params(UuidCreator.getTimeOrderedEpoch(), orderId, userId, type, message)
                .update() == 1;
    }

    /** 최신순. beforeId 가 있으면 그보다 오래된 것 (커서 페이징) */
    List<Notification> findByUser(UUID userId, UUID beforeId, int size) {
        return jdbc.sql("""
                        select id, order_id, type, message, created_at
                        from notifications
                        where user_id = :userId and (:beforeId::uuid is null or id < :beforeId::uuid)
                        order by id desc
                        limit :size
                        """)
                .param("userId", userId)
                .param("beforeId", beforeId)
                .param("size", Math.min(size, MAX_PAGE_SIZE))
                .query(Notification.class)
                .list();
    }

    record Notification(UUID id, UUID orderId, String type, String message, Instant createdAt) {
    }
}
