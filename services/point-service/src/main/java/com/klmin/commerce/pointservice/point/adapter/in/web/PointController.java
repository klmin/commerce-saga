package com.klmin.commerce.pointservice.point.adapter.in.web;

import com.klmin.commerce.pointservice.common.web.UserIdHeader;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 본인 포인트 조회. 경로에 사용자 ID 를 받지 않아 다른 사용자의 잔액을 조회할 방법이 없다 */
@RestController
@RequestMapping("/points")
class PointController {

    private final GetPointBalanceUseCase getPointBalanceUseCase;

    PointController(GetPointBalanceUseCase getPointBalanceUseCase) {
        this.getPointBalanceUseCase = getPointBalanceUseCase;
    }

    @GetMapping("/me")
    PointBalanceResponse getMyBalance(@RequestHeader(UserIdHeader.NAME) @Positive long userId) {
        PointAccount account = getPointBalanceUseCase.getBalance(userId);
        return new PointBalanceResponse(account.userId(), account.balance());
    }

    record PointBalanceResponse(long userId, long balance) {
    }
}
