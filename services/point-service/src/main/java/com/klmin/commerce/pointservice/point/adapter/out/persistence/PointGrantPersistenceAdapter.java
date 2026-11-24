package com.klmin.commerce.pointservice.point.adapter.out.persistence;

import com.klmin.commerce.pointservice.point.application.port.out.PointGrantPort;
import com.klmin.commerce.pointservice.point.domain.PointGrant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/** point_grants. 계정 생성과 같은 트랜잭션에 참여한다 (MANDATORY) */
@Component
class PointGrantPersistenceAdapter implements PointGrantPort {

    private static final String INSERT = """
            insert into point_grants (id, user_id, amount, type, created_at) values (?, ?, ?, ?, ?)
            """;

    private final JdbcClient jdbc;

    PointGrantPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(PointGrant grant) {
        jdbc.sql(INSERT)
                .params(grant.id(), grant.userId().value(), grant.amount().value(), grant.type().name(),
                        Timestamp.from(grant.createdAt()))
                .update();
    }
}
