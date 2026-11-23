package com.klmin.commerce.authservice.auth.adapter.in.web;

import com.klmin.commerce.authservice.auth.application.port.in.GetJwksUseCase;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** access token 검증용 공개키. Gateway·서비스가 받아 캐시하고, 모르는 kid 가 오면 다시 받는다 */
@RestController
class JwksController {

    private final GetJwksUseCase getJwks;

    JwksController(GetJwksUseCase getJwks) {
        this.getJwks = getJwks;
    }

    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(getJwks.jwks());
    }
}
