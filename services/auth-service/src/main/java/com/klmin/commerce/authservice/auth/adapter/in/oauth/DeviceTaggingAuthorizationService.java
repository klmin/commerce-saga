package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import com.klmin.commerce.authservice.auth.adapter.in.web.DeviceSession;
import com.klmin.commerce.authservice.auth.application.port.in.ManageDevicesUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * SAS 인가 기록에 기기 id({@code device_id})를 붙인다 (후속 5-6). 인가 요청({@code /oauth2/authorize})은 브라우저의 SSO 세션과 함께
 * 오므로 그 세션의 기기 id 를 속성으로 넣고, 뒤의 토큰 교환·갱신은 같은 기록을 이어 써 속성이 남는다.
 * 기기를 로그아웃할 때 이 속성으로 그 기기의 refresh token 만 지운다.
 * 새 인가 기록이 기기에 묶이면(SSO 로 다른 화면 로그인 = 곧 새 refresh token) 기기 기록의 보존 기간도 다시 건다
 */
public final class DeviceTaggingAuthorizationService implements OAuth2AuthorizationService {

    public static final String ATTRIBUTE = "device_id";

    private final OAuth2AuthorizationService delegate;
    private final ObjectProvider<ManageDevicesUseCase> devices;

    DeviceTaggingAuthorizationService(OAuth2AuthorizationService delegate, ObjectProvider<ManageDevicesUseCase> devices) {
        this.delegate = delegate;
        this.devices = devices;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        OAuth2Authorization tagged = tagged(authorization);
        delegate.save(tagged);
        if (tagged != authorization) {
            extendDevices(tagged.getPrincipalName());
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return delegate.findByToken(token, tokenType);
    }

    private static OAuth2Authorization tagged(OAuth2Authorization authorization) {
        if (authorization.getAttribute(ATTRIBUTE) != null
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return authorization;
        }
        HttpServletRequest request = attributes.getRequest();
        String deviceId = DeviceSession.currentDeviceId(request);
        return deviceId == null ? authorization
                : OAuth2Authorization.from(authorization).attribute(ATTRIBUTE, deviceId).build();
    }

    private void extendDevices(String principalName) {
        UserPrincipal.userId(principalName).ifPresent(userId -> devices.getObject().authorized(userId));
    }

    /**
     * SAS 설정(common.config)의 인가 기록 저장소 빈을 감싼다. 설정이 기능 패키지를 모르게 하려고 후처리로 둔다.
     * 유스케이스는 처음 쓸 때 찾는다(후처리기는 다른 빈보다 먼저 만들어진다)
     */
    @Component
    static class Wrapper implements BeanPostProcessor {

        private final ObjectProvider<ManageDevicesUseCase> devices;

        Wrapper(ObjectProvider<ManageDevicesUseCase> devices) {
            this.devices = devices;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return bean instanceof OAuth2AuthorizationService service
                    && !(bean instanceof DeviceTaggingAuthorizationService)
                    ? new DeviceTaggingAuthorizationService(service, devices) : bean;
        }
    }
}
