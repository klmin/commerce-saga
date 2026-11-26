package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import com.klmin.commerce.authservice.auth.adapter.in.web.DeviceSession;
import jakarta.servlet.http.HttpServletRequest;
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
 * 기기를 로그아웃할 때 이 속성으로 그 기기의 refresh token 만 지운다
 */
public final class DeviceTaggingAuthorizationService implements OAuth2AuthorizationService {

    public static final String ATTRIBUTE = "device_id";

    private final OAuth2AuthorizationService delegate;

    DeviceTaggingAuthorizationService(OAuth2AuthorizationService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(tagged(authorization));
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

    /** SAS 설정(common.config)의 인가 기록 저장소 빈을 감싼다. 설정이 기능 패키지를 모르게 하려고 후처리로 둔다 */
    @Component
    static class Wrapper implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return bean instanceof OAuth2AuthorizationService service
                    && !(bean instanceof DeviceTaggingAuthorizationService)
                    ? new DeviceTaggingAuthorizationService(service) : bean;
        }
    }
}
