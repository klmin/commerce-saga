package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.PriceChangeResult;

import java.time.Instant;

record PriceChangeResponse(long oldPrice, long newPrice, long changedBy, Instant changedAt) {

    static PriceChangeResponse from(PriceChangeResult r) {
        return new PriceChangeResponse(r.oldPrice(), r.newPrice(), r.changedBy(), r.changedAt());
    }
}
