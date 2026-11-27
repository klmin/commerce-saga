package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.application.port.out.CartPort;
import com.klmin.commerce.orderservice.order.domain.CartItem;
import com.klmin.commerce.orderservice.order.domain.Money;
import com.klmin.commerce.orderservice.order.domain.ProductId;
import com.klmin.commerce.orderservice.order.domain.Quantity;
import com.klmin.commerce.orderservice.order.domain.UserId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** cart_items ((user_id, product_id) PK). 엔티티 없이 JdbcClient 로 (행 하나 = 값 하나라 매핑할 것이 적다) */
@Component
class CartPersistenceAdapter implements CartPort {

    private final JdbcClient jdbc;

    CartPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CartItem> findByUserId(UserId userId) {
        return jdbc.sql("""
                        select product_id, quantity, added_price, added_at from cart_items
                        where user_id = ? order by added_at, product_id
                        """)
                .param(userId.value())
                .query((rs, n) -> new CartItem(ProductId.of(rs.getObject("product_id", UUID.class)),
                        Quantity.of(rs.getInt("quantity")), Money.of(rs.getLong("added_price")),
                        rs.getTimestamp("added_at").toInstant()))
                .list();
    }

    @Override
    public void upsert(UserId userId, CartItem item, Instant now) {
        jdbc.sql("""
                        insert into cart_items (user_id, product_id, quantity, added_price, added_at, updated_at)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (user_id, product_id) do update
                            set quantity = excluded.quantity, updated_at = excluded.updated_at
                        """)
                .params(userId.value(), item.productId().value(), item.quantity().value(), item.addedPrice().value(),
                        Timestamp.from(item.addedAt()), Timestamp.from(now))
                .update();
    }

    @Override
    public void delete(UserId userId, ProductId productId) {
        jdbc.sql("delete from cart_items where user_id = ? and product_id = ?")
                .params(userId.value(), productId.value())
                .update();
    }

    @Override
    public void deleteAll(UserId userId, Collection<ProductId> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        jdbc.sql("delete from cart_items where user_id = :userId and product_id in (:productIds)")
                .param("userId", userId.value())
                .param("productIds", productIds.stream().map(ProductId::value).toList())
                .update();
    }
}
