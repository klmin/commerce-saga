package com.klmin.commerce.inventoryservice.stock.adapter.in.web;

import com.klmin.commerce.inventoryservice.stock.application.port.in.GetStockUseCase;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 재고 조회 (상품 화면·시연용, 로그인 없이). 입고·차감은 AdminStockController (ADR 0019) */
@RestController
@RequestMapping("/stocks")
class StockController {

    private final GetStockUseCase getStockUseCase;

    StockController(GetStockUseCase getStockUseCase) {
        this.getStockUseCase = getStockUseCase;
    }

    @GetMapping("/{productId}")
    StockResponse getStock(@PathVariable UUID productId) {
        Stock stock = getStockUseCase.getStock(productId);
        return new StockResponse(stock.productId().value(), stock.available(), stock.reserved());
    }

    record StockResponse(UUID productId, int available, int reserved) {
    }
}
