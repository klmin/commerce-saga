package com.klmin.commerce.authservice.auth.adapter.in.web;

import com.klmin.commerce.authservice.auth.adapter.in.oauth.PasswordAuthenticationProvider;
import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpCommand;
import com.klmin.commerce.authservice.auth.application.port.in.SignUpUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.TokenClaimsUseCase;
import com.klmin.commerce.authservice.common.error.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

/**
 * 인증 화면의 가입 폼 {@code POST /signup} (설계 문서 4절 "가입", ADR 0017).
 * 회원 저장 + {@code UserRegistered} outbox 는 지금 가입과 같은 한 트랜잭션이다({@link SignUpUseCase}).
 * 가입하면 바로 로그인(SSO 세션)하고, 저장된 인가 요청이 있으면 그리로(인가 흐름을 이어 화면으로), 없으면 사용자 화면으로 간다.
 * 실패는 {@code register?error=…} 로 돌아간다(상대 경로라 경로 방식에서도 맞다).
 */
@Controller
class SignUpFormController {

    private final SignUpUseCase signUp;
    private final TokenClaimsUseCase claims;
    private final String webHome;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final ChangeSessionIdAuthenticationStrategy sessionFixation = new ChangeSessionIdAuthenticationStrategy();

    SignUpFormController(SignUpUseCase signUp, TokenClaimsUseCase claims, @Value("${auth.web-home}") String webHome) {
        this.signUp = signUp;
        this.claims = claims;
        this.webHome = webHome;
    }

    @PostMapping("/signup")
    void signUp(@RequestParam(defaultValue = "") String loginId, @RequestParam(defaultValue = "") String password,
                HttpServletRequest request, HttpServletResponse response) throws IOException {
        long userId;
        try {
            userId = signUp.signUp(new SignUpCommand(loginId, password));
        } catch (DuplicateLoginIdException e) {
            response.sendRedirect("register?error=duplicate");
            return;
        } catch (DomainValidationException e) {
            response.sendRedirect("register?error=invalid");
            return;
        }
        Authentication authentication = PasswordAuthenticationProvider.authenticated(claims.currentClaims(userId));
        // 로그인 폼과 같이 세션 고정 공격을 막는다(세션 id 교체)
        sessionFixation.onAuthentication(authentication, request, response);
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(authentication);
        contexts.setContext(context);
        contextRepository.saveContext(context, request, response);

        SavedRequest saved = requestCache.getRequest(request, response);
        requestCache.removeRequest(request, response);
        response.sendRedirect(savedAuthorizeRequest(saved, request).orElse(webHome));
    }

    /**
     * 저장된 요청이 SAS 인가 요청({@code <context>/oauth2/authorize})일 때만 그 경로로 돌아간다. 같은 출처의 상대 경로만 쓰고
     * 저장된 요청의 호스트는 쓰지 않는다(요청 헤더에서 온 값이라 다른 사이트로 보내지 않게)
     */
    static java.util.Optional<String> savedAuthorizeRequest(SavedRequest saved, HttpServletRequest request) {
        if (!(saved instanceof DefaultSavedRequest original)) {
            return java.util.Optional.empty();
        }
        String path = request.getContextPath() + "/oauth2/authorize";
        if (!path.equals(original.getRequestURI())) {
            return java.util.Optional.empty();
        }
        String query = original.getQueryString();
        return java.util.Optional.of(query == null ? path : path + "?" + query);
    }
}
