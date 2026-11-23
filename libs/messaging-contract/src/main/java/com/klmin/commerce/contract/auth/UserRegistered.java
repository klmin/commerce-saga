package com.klmin.commerce.contract.auth;

import java.time.Instant;

/**
 * 이벤트(auth.events): 회원이 가입했다. 메시지 키 = userId (사용자 단위 순서).
 * 받는 쪽: point(포인트 계정 생성·가입 축하 포인트) 등. 발행자는 누가 받는지 모른다.
 * 아이디·비밀번호 같은 개인정보·비밀값은 싣지 않는다 (식별자와 시각만).
 */
public record UserRegistered(long userId, Instant registeredAt) {

    public static final String TYPE = "UserRegistered";
}
