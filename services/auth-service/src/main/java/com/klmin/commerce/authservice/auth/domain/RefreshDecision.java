package com.klmin.commerce.authservice.auth.domain;

/** refresh 요청에 내민 secret 을 family 가 어떻게 볼지 */
public enum RefreshDecision {
    /** 현재 secret. 새 secret 으로 회전한다 */
    ROTATE,
    /** 방금 회전된 직전 secret 이 유예 안에 다시 옴 (여러 탭이 동시에 refresh). 회전하지 않고 access token 만 다시 준다 */
    GRACE,
    /** 이미 회전된 옛 secret 재사용 = 탈취 의심. family 를 지운다 */
    REUSE_DETECTED,
    /** family 수명이 끝남. 다시 로그인해야 한다 */
    EXPIRED
}
