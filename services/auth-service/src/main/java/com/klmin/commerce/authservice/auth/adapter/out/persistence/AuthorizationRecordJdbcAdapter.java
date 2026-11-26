package com.klmin.commerce.authservice.auth.adapter.out.persistence;

import com.klmin.commerce.authservice.auth.application.port.out.AuthorizationRecordPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * SAS 인가 기록 {@code oauth2_authorization} (SAS 의 JDBC 스키마, V4). 행 자체는 SAS 의
 * {@code JdbcOAuth2AuthorizationService} 가 쓰고, 여기서는 지우기만 한다.
 */
@Component
class AuthorizationRecordJdbcAdapter implements AuthorizationRecordPort {

    private final JdbcClient jdbc;

    AuthorizationRecordJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteByPrincipal(String principalName) {
        return jdbc.sql("delete from oauth2_authorization where principal_name = ?")
                .param(principalName)
                .update();
    }

    // greatest 는 null 을 건너뛴다. 토큰이 하나도 없는 행(인가 요청만 저장된 상태)은 끝나는 시각을 알 수 없어 남긴다.
    // 동의 화면을 끄므로 그런 행은 거의 생기지 않는다
    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("""
                        delete from oauth2_authorization
                        where greatest(authorization_code_expires_at, access_token_expires_at,
                                       oidc_id_token_expires_at, refresh_token_expires_at) < ?
                        """)
                .param(Timestamp.from(cutoff))
                .update();
    }
}
