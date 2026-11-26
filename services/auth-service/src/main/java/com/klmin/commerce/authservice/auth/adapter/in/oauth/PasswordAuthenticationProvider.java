package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import com.klmin.commerce.authservice.auth.application.InvalidCredentialsException;
import com.klmin.commerce.authservice.auth.application.LoginLockedException;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticateCommand;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticateUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.AuthenticatedUserResult;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 로그인 폼({@code POST /login})의 아이디·비밀번호를 확인한다 (ADR 0017).
 * 인증된 principal 이름은 <b>userId 문자열</b>이다. SAS 가 이 이름을 토큰의 {@code sub} 와 인가 기록의 principal 로 쓴다
 * (서비스의 {@code @CurrentUserId long} 이 그대로 동작하게, ADR 0008).
 */
@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticateUseCase useCase;

    PasswordAuthenticationProvider(AuthenticateUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        AuthenticatedUserResult user;
        try {
            user = useCase.authenticate(new AuthenticateCommand(authentication.getName(),
                    credentials == null ? null : credentials.toString()));
        } catch (LoginLockedException e) {
            throw new LockedException("로그인 실패가 많아 잠시 잠겼습니다");
        } catch (InvalidCredentialsException e) {
            throw new BadCredentialsException("아이디 또는 비밀번호가 맞지 않습니다");
        }
        return authenticated(user);
    }

    /**
     * 가입 직후 바로 로그인할 때도 같은 모양의 인증을 만든다. 역할({@code ROLE_*})과 함께 "비밀번호로 인증한 시각"
     * ({@link FactorGrantedAuthority#PASSWORD_AUTHORITY})을 넣는다. SAS 가 ID 토큰의 {@code auth_time} 을 여기서 읽는다
     */
    public static Authentication authenticated(AuthenticatedUserResult user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        user.roles().stream().sorted().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).forEach(authorities::add);
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));
        return UsernamePasswordAuthenticationToken.authenticated(Long.toString(user.userId()), null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
