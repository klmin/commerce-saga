package com.klmin.commerce.inventoryservice.stock.adapter.in.web;

import com.klmin.commerce.inventoryservice.common.web.CurrentUserId;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockResult;
import com.klmin.commerce.inventoryservice.stock.application.port.in.AdjustStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.StockAdjustmentPageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 재고 조정 (ADMIN 역할, SecurityConfig. ADR 0019). Gateway 의 /api/admin/stocks/** 가 여기로 온다.
 * 상품이 있는지는 확인하지 않는다: 상품은 product-service 의 것이고, 없는 상품 id 의 재고는 주문될 일이 없다.
 */
@RestController
@RequestMapping("/admin/stocks")
class AdminStockController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final AdjustStockUseCase adjustStockUseCase;

    AdminStockController(AdjustStockUseCase adjustStockUseCase) {
        this.adjustStockUseCase = adjustStockUseCase;
    }

    /**
     * 입고(+)·차감(-). Idempotency-Key 필수: 같은 키로 다시 보내면 다시 반영하지 않고 처음 기록을 200 으로
     * ({@code Idempotent-Replayed: true}). 같은 키에 내용이 다르면 422, 가용 재고보다 많이 빼면 409.
     */
    @PostMapping("/{productId}/adjustments")
    ResponseEntity<AdjustResponse> adjust(@CurrentUserId UUID adminId, @PathVariable UUID productId,
                                          @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                          @Valid @RequestBody AdjustRequest request) {
        AdjustStockResult result = adjustStockUseCase.adjust(
                new AdjustStockCommand(productId, request.delta(), request.reason(), adminId, idempotencyKey));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(AdjustResponse.from(result));
    }

    /** 조정 이력 (최근부터). 재고 행이 없는 상품이면 빈 목록 */
    @GetMapping("/{productId}/adjustments")
    AdjustmentPageResponse adjustments(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(AdjustStockUseCase.MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(AdjustStockUseCase.MAX_PAGE_SIZE) int size) {
        return AdjustmentPageResponse.from(adjustStockUseCase.adjustments(productId, page, size));
    }

    record AdjustRequest(
            @NotNull(message = "조정 수량을 입력해 주세요.") Integer delta,
            @Size(max = AdjustStockCommand.MAX_REASON_LENGTH, message = "사유는 200자까지입니다.") String reason) {
    }

    record AdjustmentResponse(long id, int delta, int availableAfter, String reason, UUID adjustedBy,
                              Instant adjustedAt) {

        static AdjustmentResponse from(AdjustStockResult.Adjustment a) {
            return new AdjustmentResponse(a.id(), a.delta(), a.availableAfter(), a.reason(), a.adjustedBy(),
                    a.adjustedAt());
        }
    }

    record AdjustResponse(UUID productId, int available, int reserved, AdjustmentResponse adjustment) {

        static AdjustResponse from(AdjustStockResult r) {
            return new AdjustResponse(r.productId(), r.available(), r.reserved(),
                    AdjustmentResponse.from(r.adjustment()));
        }
    }

    record AdjustmentPageResponse(List<AdjustmentResponse> items, boolean hasNext) {

        static AdjustmentPageResponse from(StockAdjustmentPageResult r) {
            return new AdjustmentPageResponse(r.items().stream().map(AdjustmentResponse::from).toList(),
                    r.hasNext());
        }
    }
}
