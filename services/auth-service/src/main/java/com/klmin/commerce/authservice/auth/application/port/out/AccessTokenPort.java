package com.klmin.commerce.authservice.auth.application.port.out;

import com.klmin.commerce.authservice.auth.domain.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** access token(JWT RS256) 서명과 공개키 */
public interface AccessTokenPort {

    AccessTokenResult issue(long userId, Set<Role> roles, Instant now, Duration ttl);

    Map<String, Object> publicJwks();
}
