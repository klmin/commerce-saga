package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.ProductAuditPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditResult;

import java.time.Instant;
import java.util.List;

/** 관리 이력 한 쪽. before 는 등록이면 null, 옮겨 온 옛 가격 이력은 price 외 값이 null */
record ProductAuditPageResponse(List<Item> items, boolean hasNext) {

    record Item(String action, Snapshot before, Snapshot after, long changedBy, Instant changedAt) {
    }

    record Snapshot(String name, String description, Long price, Boolean onSale) {

        static Snapshot from(ProductAuditResult.Snapshot s) {
            return s == null ? null : new Snapshot(s.name(), s.description(), s.price(), s.onSale());
        }
    }

    static ProductAuditPageResponse from(ProductAuditPageResult r) {
        return new ProductAuditPageResponse(r.items().stream()
                .map(i -> new Item(i.action(), Snapshot.from(i.before()), Snapshot.from(i.after()), i.changedBy(),
                        i.changedAt()))
                .toList(), r.hasNext());
    }
}
