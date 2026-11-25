package com.klmin.commerce.authservice.auth.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * <b>임시</b> 로그인·가입 화면 (후속 5-1). 5-4 에서 인증 화면 SPA({@code frontend/apps/auth})로 바꾸고 지운다.
 * <p>
 * 설계 문서 7-4 의 규칙을 미리 지킨다: 폼 action·화면 링크는 앞 {@code /} 없는 상대 경로라 경로 방식({@code /auth/...})에서도
 * 그대로 맞는다. 로그인·가입은 fetch 가 아니라 폼 POST 다(302 를 브라우저가 화면 이동으로 따라가야 인가 흐름이 이어진다).
 */
@Controller
class SignInPageController {

    @GetMapping(value = "/signin", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String signIn(HttpServletRequest request, @RequestParam(required = false) String error) {
        String message = error == null ? "" : switch (error) {
            case "locked" -> "로그인 실패가 많아 잠시 잠겼습니다. 잠시 뒤 다시 시도해 주세요.";
            default -> "아이디 또는 비밀번호가 맞지 않습니다.";
        };
        return page("로그인", message, """
                <form method="post" action="login">
                  %s
                  <label>아이디 <input name="username" autocomplete="username" required></label>
                  <label>비밀번호 <input name="password" type="password" autocomplete="current-password" required></label>
                  <button type="submit">로그인</button>
                </form>
                <p><a href="register">회원가입</a></p>
                """.formatted(csrfField(request)));
    }

    @GetMapping(value = "/register", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String register(HttpServletRequest request, @RequestParam(required = false) String error) {
        String message = error == null ? "" : switch (error) {
            case "duplicate" -> "이미 사용 중인 아이디입니다.";
            default -> "아이디는 영문 소문자·숫자·밑줄 4~30자, 비밀번호는 8~64자입니다.";
        };
        return page("회원가입", message, """
                <form method="post" action="signup">
                  %s
                  <label>아이디 <input name="loginId" autocomplete="username" required></label>
                  <label>비밀번호 <input name="password" type="password" autocomplete="new-password" required></label>
                  <button type="submit">가입하고 로그인</button>
                </form>
                <p><a href="signin">로그인</a></p>
                """.formatted(csrfField(request)));
    }

    private static String csrfField(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return "";
        }
        return "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(token.getParameterName())
                + "\" value=\"" + HtmlUtils.htmlEscape(token.getToken()) + "\">";
    }

    private static String page(String title, String message, String body) {
        String alert = message.isEmpty() ? "" : "<p role=\"alert\">" + HtmlUtils.htmlEscape(message) + "</p>";
        return """
                <!doctype html>
                <html lang="ko"><head><meta charset="utf-8"><title>%s</title>
                <meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body><main><h1>%s</h1>%s%s</main></body></html>
                """.formatted(title, title, alert, body);
    }
}
