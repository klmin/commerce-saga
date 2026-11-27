package com.klmin.commerce.inventoryservice.stock.application;

import com.klmin.commerce.inventoryservice.common.error.NotFoundException;

import java.util.UUID;

public class StockNotFoundException extends NotFoundException {

    public StockNotFoundException(UUID productId) {
        super("stock not found: productId=" + productId);
    }
}
