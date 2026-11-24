package com.klmin.commerce.pointservice.point.application.port.out;

import com.klmin.commerce.pointservice.point.domain.PointGrant;

public interface PointGrantPort {

    /** 지급 기록 저장. 잔액을 바꾼 트랜잭션 안에서 부른다 */
    void save(PointGrant grant);
}
