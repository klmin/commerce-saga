package com.klmin.commerce.inventoryservice.stock.application.port.in;

import com.klmin.commerce.inventoryservice.stock.domain.Stock;

import java.util.UUID;

public interface GetStockUseCase {

    Stock getStock(UUID productId);
}
