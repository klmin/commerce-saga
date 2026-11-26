package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import com.klmin.commerce.authservice.auth.application.InvalidCredentialsException;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticatedUserResult;
import com.klmin.commerce.authservice.auth.application.port.in.TokenClaimsUseCase;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * SAS 가 만드는 access token·ID token 에 {@code roles} 를 넣는다 (ADR 0017, 클레임은 ADR 0008 과 같다).
 * {@code sub} 는 principal 이름(= userId, {@link PasswordAuthenticationProvider}) 그대로다.
 * <p>
 * 역할은 로그인 때 값이 아니라 <b>토큰을 만들 때마다 DB 에서</b> 읽는다. refresh 로 새 토큰을 받을 때 역할 변경이 반영되고,
 * 정지·삭제된 사용자는 {@code invalid_grant} 로 갱신이 거절된다.
 */
@Component
class UserClaimsTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final TokenClaimsUseCase useCase;

    UserClaimsTokenCustomizer(TokenClaimsUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
        boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
        if (!accessToken && !idToken) {
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(context.getPrincipal().getName());
        } catch (NumberFormatException e) {
            // 우리 로그인 폼을 거치지 않은 principal. client_credentials 같은 grant 는 등록하지 않는다
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
        }
        AuthenticatedUserResult user;
        try {
            user = useCase.currentClaims(userId);
        } catch (InvalidCredentialsException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    "사용할 수 없는 계정", null));
        }
        // 변경 가능한 ArrayList 로: SAS 는 토큰 클레임을 인가 기록(JSON)에 저장하고 다시 읽는데, 역직렬화 허용 목록에
        // List.of 계열(ImmutableCollections)은 없다
        context.getClaims().claim("roles", new ArrayList<>(user.roles().stream().sorted().toList()));
    }
}
