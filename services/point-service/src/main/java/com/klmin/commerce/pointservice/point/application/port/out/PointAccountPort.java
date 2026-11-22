package com.klmin.commerce.pointservice.point.application.port.out;

import com.klmin.commerce.pointservice.point.domain.PointAccount;

import java.util.Optional;

public interface PointAccountPort {

    Optional<PointAccount> findById(long userId);

    /** 계정 행을 비관적 락(SELECT ... FOR UPDATE)으로 잠가 가져온다. 같은 사용자의 동시 차감·환불이 순서대로 처리된다 */
    Optional<PointAccount> findByIdForUpdate(long userId);

    void update(PointAccount account);
}
