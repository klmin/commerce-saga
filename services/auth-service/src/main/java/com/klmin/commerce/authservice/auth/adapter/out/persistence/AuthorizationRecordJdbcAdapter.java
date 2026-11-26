package com.klmin.commerce.authservice.auth.adapter.out.persistence;

import com.klmin.commerce.authservice.auth.application.port.out.AuthorizationRecordPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SAS 인가 기록 {@code oauth2_authorization} (SAS 의 JDBC 스키마, V4). 행 자체는 SAS 의
 * {@code JdbcOAuth2AuthorizationService} 가 쓰고, 여기서는 지우고 기기별로 세기만 한다.
 * 기기 id 는 인가 기록의 속성 {@code device_id}(attributes JSON 안, {@code DeviceTaggingAuthorizationService})다.
 * 기기 id 는 UUID 라 attributes 문자열에서 그대로 찾는다(다른 값과 겹치지 않는다)
 */
@Component
class AuthorizationRecordJdbcAdapter implements AuthorizationRecordPort {

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DEVICE_ID = Pattern.compile("\"device_id\"\\s*:\\s*\"(" + UUID.pattern() + ")\"");

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

    @Override
    public int deleteByDevice(String principalName, String deviceId) {
        if (!UUID.matcher(deviceId).matches()) {
            return 0; // LIKE 의 % · _ 가 섞인 값으로 다른 행을 지우지 않게
        }
        return jdbc.sql("delete from oauth2_authorization where principal_name = ? and attributes like ?")
                .param(principalName)
                .param("%\"" + deviceId + "\"%")
                .update();
    }

    @Override
    public Map<String, Instant> liveDevices(String principalName, Instant now) {
        Map<String, Instant> result = new HashMap<>();
        jdbc.sql("""
                        select attributes, greatest(access_token_issued_at, refresh_token_issued_at) as seen
                        from oauth2_authorization
                        where principal_name = ? and refresh_token_expires_at > ?
                        """)
                .param(principalName)
                .param(Timestamp.from(now))
                .query((rs, n) -> {
                    String attributes = rs.getString("attributes");
                    Timestamp seen = rs.getTimestamp("seen");
                    Matcher m = attributes == null ? null : DEVICE_ID.matcher(attributes);
                    if (m != null && m.find() && seen != null) {
                        result.merge(m.group(1), seen.toInstant(), (a, b) -> a.isAfter(b) ? a : b);
                    }
                    return null;
                })
                .list();
        return result;
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
