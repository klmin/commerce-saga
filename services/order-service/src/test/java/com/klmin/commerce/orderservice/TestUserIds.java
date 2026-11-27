package com.klmin.commerce.orderservice;

import java.util.UUID;

/**
 * 테스트용 고정 사용자 id (ADR 0018). {@code uid(n)} = {@code 01900000-0000-7000-8001-00000000000n} 형태라
 * 1~4 는 시드 사용자 user1~user4, 100 은 시드 관리자 id 와 같다.
 */
public final class TestUserIds {

    private TestUserIds() {
    }

    public static UUID uid(long n) {
        return UUID.fromString(String.format("01900000-0000-7000-8001-%012d", n));
    }
}
