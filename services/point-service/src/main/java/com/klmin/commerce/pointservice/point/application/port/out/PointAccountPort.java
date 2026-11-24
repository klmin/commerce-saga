package com.klmin.commerce.pointservice.point.application.port.out;

import com.klmin.commerce.pointservice.point.domain.PointAccount;
import com.klmin.commerce.pointservice.point.domain.UserId;

import java.util.Optional;

public interface PointAccountPort {

    Optional<PointAccount> findById(UserId userId);

    /** 계정 행을 비관적 락(SELECT ... FOR UPDATE)으로 잠가 가져온다. 같은 사용자의 동시 차감·환불이 순서대로 처리된다 */
    Optional<PointAccount> findByIdForUpdate(UserId userId);

    void update(PointAccount account);

    /**
     * 계정이 없을 때만 만든다 (user_id 유니크, insert ... on conflict do nothing). 같은 사용자 계정을 동시에 만들어도
     * 하나만 생긴다.
     *
     * @return 만들었으면 true, 이미 있으면 false
     */
    boolean insertIfAbsent(PointAccount account);
}
