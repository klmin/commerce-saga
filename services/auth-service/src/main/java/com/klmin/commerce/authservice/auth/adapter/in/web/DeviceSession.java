package com.klmin.commerce.authservice.auth.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** SSO 로그인 세션에 둔 기기 id (후속 5-6). 로그인할 때 {@link DeviceLoginRecorder} 가 넣는다 */
public final class DeviceSession {

    public static final String ATTRIBUTE = "commerce.deviceId";

    private DeviceSession() {
    }

    /** 이 요청의 SSO 세션의 기기 id. 세션이 없거나 기기를 남기기 전에 로그인한 세션이면 null */
    public static String currentDeviceId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(ATTRIBUTE);
    }
}
