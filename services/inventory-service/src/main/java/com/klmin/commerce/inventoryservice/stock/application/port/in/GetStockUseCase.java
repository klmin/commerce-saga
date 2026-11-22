package com.klmin.commerce.inventoryservice.stock.application.port.in;

import com.klmin.commerce.inventoryservice.stock.domain.Stock;

public interface GetStockUseCase {

    Stock getStock(long productId);
}
