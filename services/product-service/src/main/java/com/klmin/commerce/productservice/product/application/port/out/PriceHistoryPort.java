package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.domain.PriceChange;

public interface PriceHistoryPort {

    void save(PriceChange change);
}
