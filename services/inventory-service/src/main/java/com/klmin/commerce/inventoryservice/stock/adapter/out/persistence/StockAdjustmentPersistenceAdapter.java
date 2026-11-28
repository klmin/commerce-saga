package com.klmin.commerce.inventoryservice.stock.adapter.out.persistence;

import com.klmin.commerce.inventoryservice.stock.application.port.out.StockAdjustmentPort;
import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.StockAdjustment;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 재고 조정 이력 (추가 전용 로그, ADR 0019). 엔티티 없이 SQL 로 넣고 읽는다(바꾸지 않는 행이라 더티 체킹이 필요 없다).
 * 같은 관리자·같은 키는 유니크 제약으로 한 번만 들어간다: 동시에 오면 뒤 INSERT 는 앞 트랜잭션이 끝날 때까지 기다렸다가
 * 앞이 커밋하면 0건(on conflict do nothing), 롤백하면 1건.
 */
@Component
class StockAdjustmentPersistenceAdapter implements StockAdjustmentPort {

    private static final String COLUMNS =
            "id, product_id, delta, available_after, reason, adjusted_by, idempotency_key, adjusted_at";

    private static final RowMapper<StockAdjustment> MAPPER = (rs, _) -> new StockAdjustment(
            rs.getLong("id"),
            ProductId.of(rs.getObject("product_id", UUID.class)),
            rs.getInt("delta"),
            rs.getLong("available_after"),
            rs.getString("reason"),
            rs.getObject("adjusted_by", UUID.class),
            rs.getString("idempotency_key"),
            rs.getTimestamp("adjusted_at").toInstant());

    private final JdbcClient jdbc;

    StockAdjustmentPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StockAdjustment> findByKey(UUID adjustedBy, String idempotencyKey) {
        return jdbc.sql("select " + COLUMNS + " from stock_adjustments where adjusted_by = ? and idempotency_key = ?")
                .params(adjustedBy, idempotencyKey)
                .query(MAPPER)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StockAdjustment> insert(StockAdjustment a) {
        return jdbc.sql("""
                        insert into stock_adjustments
                            (product_id, delta, available_after, reason, adjusted_by, idempotency_key, adjusted_at)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (adjusted_by, idempotency_key) do nothing
                        returning\s""" + COLUMNS)
                .params(a.productId().value(), a.delta(), a.availableAfter(), a.reason(), a.adjustedBy(),
                        a.idempotencyKey(), Timestamp.from(a.adjustedAt()))
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<StockAdjustment> findByProduct(ProductId productId, int offset, int limit) {
        return jdbc.sql("select " + COLUMNS + " from stock_adjustments where product_id = ? order by id desc"
                        + " offset ? limit ?")
                .params(productId.value(), offset, limit)
                .query(MAPPER)
                .list();
    }
}
