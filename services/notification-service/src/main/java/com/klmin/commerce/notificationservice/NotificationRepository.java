package com.klmin.commerce.notificationservice;

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

    /** @return 새로 만들었으면 true, 같은 주문·종류의 알림이 이미 있으면 false (중복 이벤트) */
    boolean insertIfAbsent(UUID orderId, long userId, String type, String message) {
        return jdbc.sql("""
                        insert into notifications (order_id, user_id, type, message)
                        values (?, ?, ?, ?)
                        on conflict (order_id, type) do nothing
                        """)
                .params(orderId, userId, type, message)
                .update() == 1;
    }

    /** 최신순. beforeId 가 있으면 그보다 오래된 것 (커서 페이징) */
    List<Notification> findByUser(long userId, Long beforeId, int size) {
        return jdbc.sql("""
                        select id, order_id, type, message, created_at
                        from notifications
                        where user_id = :userId and (:beforeId::bigint is null or id < :beforeId)
                        order by id desc
                        limit :size
                        """)
                .param("userId", userId)
                .param("beforeId", beforeId)
                .param("size", Math.min(size, MAX_PAGE_SIZE))
                .query(Notification.class)
                .list();
    }

    record Notification(long id, UUID orderId, String type, String message, Instant createdAt) {
    }
}
