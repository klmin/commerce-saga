package com.klmin.commerce.inventoryservice.stock.application;

import com.klmin.commerce.inventoryservice.common.error.NotFoundException;

public class StockNotFoundException extends NotFoundException {

    public StockNotFoundException(long productId) {
        super("stock not found: productId=" + productId);
    }
}
