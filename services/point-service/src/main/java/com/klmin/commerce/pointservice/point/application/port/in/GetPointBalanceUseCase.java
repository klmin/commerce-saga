package com.klmin.commerce.pointservice.point.application.port.in;

import com.klmin.commerce.pointservice.point.domain.PointAccount;

public interface GetPointBalanceUseCase {

    /** 요청한 사용자 본인의 계정만 조회한다. 없으면 PointAccountNotFoundException */
    PointAccount getBalance(long userId);
}
