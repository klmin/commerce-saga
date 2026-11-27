package com.klmin.commerce.authservice.auth.adapter.in.web;

import com.klmin.commerce.authservice.auth.adapter.in.oauth.UserPrincipal;
import com.klmin.commerce.authservice.auth.application.port.in.DeviceResult;
import com.klmin.commerce.authservice.auth.application.port.in.ManageDevicesUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 로그인 기기 API (후속 5-6). 인증 화면 SPA({@code /devices})가 SSO 세션 쿠키({@code AUTH_SESSION})로 부른다(토큰을 쓰지 않는다).
 * 사용자는 SSO 세션의 로그인 사용자이고, 기기는 그 사용자 것만 보이고 지운다(남의 기기 id 는 404).
 * 지우는 요청은 CSRF 토큰(헤더, {@code GET csrf} 의 {@code headerName})이 필요하다. 로그인하지 않았으면 401(로그인 체인 설정)
 */
@RestController
@RequestMapping("/sessions")
class DevicesController {

    private final ManageDevicesUseCase devices;

    DevicesController(ManageDevicesUseCase devices) {
        this.devices = devices;
    }

    @GetMapping
    ResponseEntity<List<DeviceResponse>> list(Authentication authentication, HttpServletRequest request) {
        List<DeviceResponse> body = devices.devices(userId(authentication), DeviceSession.currentDeviceId(request))
                .stream().map(DeviceResponse::of).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @DeleteMapping("/{deviceId}")
    ResponseEntity<Void> logout(@PathVariable String deviceId, Authentication authentication,
                                HttpServletRequest request) {
        devices.logout(userId(authentication), deviceId, DeviceSession.currentDeviceId(request));
        return ResponseEntity.noContent().build();
    }

    /** 지금 기기를 뺀 모든 기기 로그아웃 */
    @DeleteMapping
    ResponseEntity<Void> logoutOthers(Authentication authentication, HttpServletRequest request) {
        devices.logoutOthers(userId(authentication), DeviceSession.currentDeviceId(request));
        return ResponseEntity.noContent().build();
    }

    /**
     * 화면(사용자 화면의 "로그인 기기" 링크)이 여는 주소. 로그인돼 있으면 기기 화면으로, 아니면 로그인 화면을 거쳐 여기로 돌아온다
     * (로그인 체인이 이 요청을 저장했다가 로그인 뒤 다시 보낸다). 경로 방식이면 context path 에 /auth 가 붙어 있다
     */
    @GetMapping("/login")
    void openDevices(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(request.getContextPath() + "/devices");
    }

    /** SSO 세션의 사용자는 우리 로그인 폼이 넣은 userId 다. 아니면 기기가 없는 사용자라 401 */
    private static UUID userId(Authentication authentication) {
        return UserPrincipal.userId(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "userId 로 로그인하지 않았습니다"));
    }

    record DeviceResponse(String id, boolean current, String userAgent, Instant loginAt, Instant lastSeenAt) {
        static DeviceResponse of(DeviceResult v) {
            return new DeviceResponse(v.id(), v.current(), v.userAgent(), v.loginAt(), v.lastSeenAt());
        }
    }
}
