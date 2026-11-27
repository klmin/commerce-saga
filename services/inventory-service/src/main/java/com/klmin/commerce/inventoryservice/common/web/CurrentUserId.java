package com.klmin.commerce.inventoryservice.common.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 요청한 사용자 id = access token(JWT) 의 {@code sub} (ADR 0008, UUID 는 ADR 0018).
 * 경로·본문으로 사용자 id 를 받지 않으므로 다른 사용자로 행세할 방법이 없다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "T(java.util.UUID).fromString(subject)")
public @interface CurrentUserId {
}
