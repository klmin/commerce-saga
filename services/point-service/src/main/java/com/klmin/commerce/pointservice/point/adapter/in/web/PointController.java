package com.klmin.commerce.pointservice.point.adapter.in.web;

import com.klmin.commerce.pointservice.common.web.CurrentUserId;
import com.klmin.commerce.pointservice.point.application.port.in.GetPointBalanceUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryQuery;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 본인 포인트 조회. 경로에 사용자 ID 를 받지 않아 다른 사용자의 잔액·내역을 조회할 방법이 없다 */
@RestController
@RequestMapping("/points")
class PointController {

    private final GetPointBalanceUseCase getPointBalanceUseCase;
    private final ListMyPointHistoryUseCase listMyPointHistoryUseCase;

    PointController(GetPointBalanceUseCase getPointBalanceUseCase,
                    ListMyPointHistoryUseCase listMyPointHistoryUseCase) {
        this.getPointBalanceUseCase = getPointBalanceUseCase;
        this.listMyPointHistoryUseCase = listMyPointHistoryUseCase;
    }

    @GetMapping("/me")
    PointBalanceResponse getMyBalance(@CurrentUserId long userId) {
        PointAccount account = getPointBalanceUseCase.getBalance(userId);
        return new PointBalanceResponse(account.userId().value(), account.balance().value());
    }

    /** 본인 포인트 내역 (주문 사용 + 지급, 최신순 커서 페이징). 다음 쪽은 마지막 entryId 를 before 로 */
    @GetMapping("/me/history")
    List<PointHistoryEntryResponse> getMyHistory(@CurrentUserId long userId,
                                                 @RequestParam(required = false) UUID before,
                                                 @RequestParam(defaultValue = "20") @Min(1)
                                                 @Max(ListMyPointHistoryQuery.MAX_SIZE) int size) {
        return listMyPointHistoryUseCase.listMyHistory(new ListMyPointHistoryQuery(userId, before, size)).stream()
                .map(PointHistoryEntryResponse::from)
                .toList();
    }

    record PointBalanceResponse(long userId, long balance) {
    }

    /** kind: ORDER(주문 사용) 또는 지급 종류(SIGNUP_BONUS). status: USED·REJECTED·REFUNDED 또는 GRANTED */
    record PointHistoryEntryResponse(UUID entryId, String kind, UUID orderId, long amount, String status,
                                     String reason, Instant createdAt, Instant updatedAt) {

        static PointHistoryEntryResponse from(PointHistoryEntryResult r) {
            return new PointHistoryEntryResponse(r.entryId(), r.kind(), r.orderId(), r.amount(), r.status(),
                    r.reason(), r.createdAt(), r.updatedAt());
        }
    }
}
