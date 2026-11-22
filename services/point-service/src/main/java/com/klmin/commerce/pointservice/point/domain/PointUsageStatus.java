package com.klmin.commerce.pointservice.point.domain;

public enum PointUsageStatus {
    /** 차감했다 */
    USED,
    /** 차감하지 못했다 (잔액 부족 등) */
    REJECTED,
    /** 보상으로 돌려줬다 (또는 사용 전에 환불 요청이 왔다) */
    REFUNDED
}
