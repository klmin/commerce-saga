package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.Map;

public interface GetJwksUseCase {

    /** access token 검증용 공개키 (JWK Set JSON). Gateway·서비스가 캐시해 쓴다 */
    Map<String, Object> jwks();
}
