package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * 같은 Idempotency-Key 로 온 요청이 "같은 주문"인지 비교하는 해시 (SHA-256 hex).
 * 사용자·키는 이미 같으므로 주문 내용(줄, 사용 포인트)만 넣는다. 줄 순서는 상품 id(UUID 소문자 문자열) 로 정렬해 무시한다.
 */
final class OrderRequestHash {

    private OrderRequestHash() {
    }

    static String of(PlaceOrderCommand command) {
        String canonical = command.lines().stream()
                .sorted(Comparator.comparing((PlaceOrderCommand.Line l) -> l.productId().toString()))
                .map(l -> l.productId() + ":" + l.quantity() + ":" + l.unitPrice())
                .collect(Collectors.joining(";")) + "|point=" + command.pointAmount();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
