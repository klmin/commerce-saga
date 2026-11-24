package com.klmin.commerce.inventoryservice.stock.adapter.in.web;

import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재고 조회 (시연·확인용). 재고 관리 API 는 관리자 권한이 생기는 4단계 이후 */
@RestController
@RequestMapping("/stocks")
class StockController {

    private final GetStockUseCase getStockUseCase;

    StockController(GetStockUseCase getStockUseCase) {
        this.getStockUseCase = getStockUseCase;
    }

    @GetMapping("/{productId}")
    StockResponse getStock(@PathVariable @Positive long productId) {
        Stock stock = getStockUseCase.getStock(productId);
        return new StockResponse(stock.productId().value(), stock.available(), stock.reserved());
    }

    record StockResponse(long productId, int available, int reserved) {
    }
}
